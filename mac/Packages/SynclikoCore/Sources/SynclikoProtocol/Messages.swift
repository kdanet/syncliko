import Foundation

public enum Platform: String, Codable, Sendable { case macos, android }

public struct Hello: Codable, Sendable, Equatable {
    public var protocolVersion: Int
    public var deviceId: String
    public var deviceName: String
    public var platform: Platform
    public var appVersion: String

    public init(protocolVersion: Int = SynclikoProtocol.version, deviceId: String, deviceName: String, platform: Platform, appVersion: String) {
        self.protocolVersion = protocolVersion
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.appVersion = appVersion
    }
}

public struct PairRequest: Codable, Sendable, Equatable {
    public var token: String
    public var deviceId: String
    public var deviceName: String
    public var platform: Platform

    public init(token: String, deviceId: String, deviceName: String, platform: Platform) {
        self.token = token
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
    }
}

public struct PairResult: Codable, Sendable, Equatable {
    public var accepted: Bool
    public var deviceId: String
    public var deviceName: String
    public var reason: String?

    public init(accepted: Bool, deviceId: String, deviceName: String, reason: String? = nil) {
        self.accepted = accepted
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.reason = reason
    }
}

public enum ClipKind: String, Codable, Sendable { case text, url, image }

public struct ClipHeader: Codable, Sendable, Equatable {
    public var id: String
    public var origin: String
    public var createdAt: Int64
    public var kind: ClipKind
    public var mime: String
    public var size: Int
    public var sha256: String
    public var sensitive: Bool

    public init(id: String, origin: String, createdAt: Int64, kind: ClipKind, mime: String, size: Int, sha256: String, sensitive: Bool) {
        self.id = id
        self.origin = origin
        self.createdAt = createdAt
        self.kind = kind
        self.mime = mime
        self.size = size
        self.sha256 = sha256
        self.sensitive = sensitive
    }
}

public struct ClipChunk: Codable, Sendable, Equatable {
    public var id: String
    public var offset: Int
    public init(id: String, offset: Int) {
        self.id = id
        self.offset = offset
    }
}

public struct Ack: Codable, Sendable, Equatable {
    public var id: String
    public init(id: String) { self.id = id }
}

public struct Ping: Codable, Sendable, Equatable {
    public var t: Int64
    public init(t: Int64) { self.t = t }
}

public struct Endpoint: Codable, Sendable, Equatable, Hashable {
    public enum Kind: String, Codable, Sendable { case lan, tailscale, bonjour }
    public var host: String
    public var port: Int
    public var kind: Kind
    public init(host: String, port: Int, kind: Kind) {
        self.host = host
        self.port = port
        self.kind = kind
    }
}

public struct Endpoints: Codable, Sendable, Equatable {
    public var endpoints: [Endpoint]
    public init(endpoints: [Endpoint]) { self.endpoints = endpoints }
}

public struct ProtocolErrorMessage: Codable, Sendable, Equatable, Error {
    public enum Code: String, Codable, Sendable {
        case version, unauthorized, badFrame, unknownType, tooLarge, hashMismatch, pairingClosed, badToken, rejected
    }
    public var code: Code
    public var message: String
    public init(code: Code, message: String) {
        self.code = code
        self.message = message
    }
}

struct Empty: Codable {}

/// A decoded, typed protocol message.
public enum Message: Sendable, Equatable {
    case hello(Hello)
    case pairRequest(PairRequest)
    case pairResult(PairResult)
    case clip(ClipHeader, Data)
    case clipChunk(ClipChunk, Data)
    case ack(Ack)
    case ping(Ping)
    case pong(Ping)
    case requestLatest
    case endpoints(Endpoints)
    case error(ProtocolErrorMessage)

    public var type: FrameType {
        switch self {
        case .hello: .hello
        case .pairRequest: .pairRequest
        case .pairResult: .pairResult
        case .clip: .clip
        case .clipChunk: .clipChunk
        case .ack: .ack
        case .ping: .ping
        case .pong: .pong
        case .requestLatest: .requestLatest
        case .endpoints: .endpoints
        case .error: .error
        }
    }
}

public enum MessageDecodingError: Error, Equatable, Sendable {
    case unknownType(UInt8)
    case badHeader(FrameType)
}

public enum SynclikoProtocol {
    public static let version = 1
    public static let defaultPort: UInt16 = 47823
    public static let bonjourType = "_syncliko._tcp"
    public static let alpn = "syncliko/1"
    public static let chunkSize = 262_144
    public static let maxClipSize = 20 * 1_048_576
    public static let latestMaxAge: TimeInterval = 120
    public static let pingInterval: TimeInterval = 45
    public static let idleTimeout: TimeInterval = 120

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    static let decoder = JSONDecoder()

    public static func encode(_ message: Message) -> Frame {
        func h<T: Encodable>(_ v: T) -> Data { (try? encoder.encode(v)) ?? Data("{}".utf8) }
        let type = message.type.rawValue
        switch message {
        case .hello(let v): return Frame(rawType: type, header: h(v))
        case .pairRequest(let v): return Frame(rawType: type, header: h(v))
        case .pairResult(let v): return Frame(rawType: type, header: h(v))
        case .clip(let v, let p): return Frame(rawType: type, header: h(v), payload: p)
        case .clipChunk(let v, let p): return Frame(rawType: type, header: h(v), payload: p)
        case .ack(let v): return Frame(rawType: type, header: h(v))
        case .ping(let v), .pong(let v): return Frame(rawType: type, header: h(v))
        case .requestLatest: return Frame(rawType: type, header: h(Empty()))
        case .endpoints(let v): return Frame(rawType: type, header: h(v))
        case .error(let v): return Frame(rawType: type, header: h(v))
        }
    }

    public static func decode(_ frame: Frame) throws -> Message {
        guard let type = frame.type else { throw MessageDecodingError.unknownType(frame.rawType) }
        func h<T: Decodable>(_: T.Type) throws -> T {
            do { return try decoder.decode(T.self, from: frame.header) } catch { throw MessageDecodingError.badHeader(type) }
        }
        switch type {
        case .hello: return .hello(try h(Hello.self))
        case .pairRequest: return .pairRequest(try h(PairRequest.self))
        case .pairResult: return .pairResult(try h(PairResult.self))
        case .clip: return .clip(try h(ClipHeader.self), frame.payload)
        case .clipChunk: return .clipChunk(try h(ClipChunk.self), frame.payload)
        case .ack: return .ack(try h(Ack.self))
        case .ping: return .ping(try h(Ping.self))
        case .pong: return .pong(try h(Ping.self))
        case .requestLatest: return .requestLatest
        case .endpoints: return .endpoints(try h(Endpoints.self))
        case .error: return .error(try h(ProtocolErrorMessage.self))
        }
    }
}
