import CryptoKit
import Foundation
import Network
import Security
import SwiftASN1
import SynclikoProtocol
import X509

/// Long-lived self-signed ECDSA P-256 identity used for mutual TLS (PROTOCOL.md §1).
public struct TLSIdentity: Sendable {
    public let privateKey: P256.Signing.PrivateKey
    public let certificateDER: Data

    /// SHA-256 of the DER certificate — the value peers pin.
    public var fingerprint: Data { Data(SHA256.hash(data: certificateDER)) }

    public init(privateKey: P256.Signing.PrivateKey, certificateDER: Data) {
        self.privateKey = privateKey
        self.certificateDER = certificateDER
    }

    public static func generate(commonName: String) throws -> TLSIdentity {
        let key = P256.Signing.PrivateKey()
        let name = try DistinguishedName {
            CommonName(commonName)
            OrganizationName("Syncliko")
        }
        let now = Date()
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: .init(key.publicKey),
            notValidBefore: now.addingTimeInterval(-86_400),
            notValidAfter: now.addingTimeInterval(86_400 * 365 * 20),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
                Critical(KeyUsage(digitalSignature: true))
                try ExtendedKeyUsage([.serverAuth, .clientAuth])
            },
            issuerPrivateKey: .init(key)
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return TLSIdentity(privateKey: key, certificateDER: Data(serializer.serializedBytes))
    }

    func secIdentity() throws -> SecIdentity {
        guard let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData) else {
            throw TransportError.identity("invalid certificate")
        }
        var error: Unmanaged<CFError>?
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass: kSecAttrKeyClassPrivate,
        ]
        guard let key = SecKeyCreateWithData(privateKey.x963Representation as CFData, attributes as CFDictionary, &error) else {
            throw TransportError.identity(error?.takeRetainedValue().localizedDescription ?? "invalid key")
        }
        guard let identity = SecIdentityCreate(nil, certificate, key) else {
            throw TransportError.identity("key does not match certificate")
        }
        return identity
    }
}

/// Stores the identity in the user's keychain as a generic password item.
public struct KeychainIdentityStore: Sendable {
    public var service: String
    public var account: String

    public init(service: String = "com.syncliko.identity", account: String = "default") {
        self.service = service
        self.account = account
    }

    private struct Blob: Codable {
        var key: Data
        var certificate: Data
    }

    public func loadOrCreate(commonName: String) throws -> TLSIdentity {
        if let existing = try load() { return existing }
        let identity = try TLSIdentity.generate(commonName: commonName)
        try save(identity)
        return identity
    }

    public func load() throws -> TLSIdentity? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw TransportError.keychain(status) }
        let blob = try JSONDecoder().decode(Blob.self, from: data)
        return TLSIdentity(privateKey: try P256.Signing.PrivateKey(rawRepresentation: blob.key), certificateDER: blob.certificate)
    }

    public func save(_ identity: TLSIdentity) throws {
        let data = try JSONEncoder().encode(Blob(key: identity.privateKey.rawRepresentation, certificate: identity.certificateDER))
        let base: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData] = data
        add[kSecAttrLabel] = "Syncliko device identity"
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw TransportError.keychain(status) }
    }

    public func delete() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account] as CFDictionary)
    }
}

public enum TransportError: Error, Sendable, CustomStringConvertible {
    case identity(String)
    case keychain(OSStatus)
    case notConnected
    case protocolViolation(String)

    public var description: String {
        switch self {
        case .identity(let s): "TLS identity error: \(s)"
        case .keychain(let s): "Keychain error \(s): \(SecCopyErrorMessageString(s, nil) as String? ?? "")"
        case .notConnected: "Not connected"
        case .protocolViolation(let s): "Protocol violation: \(s)"
        }
    }
}

enum TLS {
    /// Builds mutual-TLS options. `verify` receives the peer's leaf-certificate fingerprint.
    static func options(identity: TLSIdentity, queue: DispatchQueue, verify: @escaping @Sendable (Data) -> Bool) throws -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        let sec = options.securityProtocolOptions
        guard let local = sec_identity_create(try identity.secIdentity()) else {
            throw TransportError.identity("sec_identity_create failed")
        }
        sec_protocol_options_set_local_identity(sec, local)
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_add_tls_application_protocol(sec, SynclikoProtocol.alpn)
        sec_protocol_options_set_peer_authentication_required(sec, true)
        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate], let leaf = chain.first else {
                complete(false)
                return
            }
            let der = SecCertificateCopyData(leaf) as Data
            complete(verify(Data(SHA256.hash(data: der))))
        }, queue)
        return options
    }

    static func parameters(_ tls: NWProtocolTLS.Options) -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 30
        tcp.keepaliveInterval = 10
        tcp.keepaliveCount = 3
        tcp.connectionTimeout = 8
        let params = NWParameters(tls: tls, tcp: tcp)
        params.includePeerToPeer = false
        return params
    }

    /// Reads the peer's leaf certificate fingerprint from an established connection.
    static func peerFingerprint(of connection: NWConnection) -> Data? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        var leaf: Data?
        sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { certificate in
            if leaf == nil {
                leaf = SecCertificateCopyData(sec_certificate_copy_ref(certificate).takeRetainedValue()) as Data
            }
        }
        return leaf.map { Data(SHA256.hash(data: $0)) }
    }
}
