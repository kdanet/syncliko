package com.syncliko.transport

import com.syncliko.protocol.SynclikoProtocol
import com.syncliko.protocol.sha256
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** This device's long-lived TLS identity (Android Keystore on the phone, a test key on the JVM). */
interface DeviceIdentity {
    val privateKey: PrivateKey
    val certificate: X509Certificate
    val fingerprint: ByteArray get() = certificate.encoded.sha256()
}

class PinMismatchException(message: String) : CertificateException(message)

/** Mutual TLS 1.3 with SHA-256 certificate pinning (PROTOCOL.md §1). */
object Tls {
    private const val ALIAS = "syncliko"

    fun context(identity: DeviceIdentity, acceptPeer: (ByteArray) -> Boolean): SSLContext {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?) = identity.privateKey
        }
        val trustManager = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no peer certificate")
                if (!acceptPeer(leaf.encoded.sha256())) throw PinMismatchException("peer certificate is not pinned")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom()) }
    }

    /** Opens TCP, then a TLS 1.3 session pinned to [serverFingerprint]. Blocking. */
    fun connect(
        identity: DeviceIdentity,
        host: String,
        port: Int,
        serverFingerprint: ByteArray,
        connectTimeoutMs: Int = 4_000,
        handshakeTimeoutMs: Int = 8_000,
    ): SSLSocket {
        val tcp = Socket()
        try {
            tcp.tcpNoDelay = true
            tcp.keepAlive = true
            tcp.connect(InetSocketAddress(host, port), connectTimeoutMs)
            tcp.soTimeout = handshakeTimeoutMs
            val ssl = context(identity) { it.contentEquals(serverFingerprint) }
                .socketFactory.createSocket(tcp, host, port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.sslParameters = ssl.sslParameters.apply { applicationProtocols = arrayOf(SynclikoProtocol.ALPN) }
            ssl.startHandshake()
            ssl.soTimeout = SynclikoProtocol.IDLE_TIMEOUT_MS.toInt()
            return ssl
        } catch (e: Exception) {
            runCatching { tcp.close() }
            throw e
        }
    }

    /** Wraps a key and certificate (e.g. loaded from a PKCS#12 file) as an identity. */
    fun identityOf(key: PrivateKey, cert: X509Certificate) = object : DeviceIdentity {
        override val privateKey = key
        override val certificate = cert
    }

    fun identityFromKeyStore(keyStore: KeyStore, alias: String, password: CharArray? = null): DeviceIdentity =
        identityOf(keyStore.getKey(alias, password) as PrivateKey, keyStore.getCertificate(alias) as X509Certificate)
}
