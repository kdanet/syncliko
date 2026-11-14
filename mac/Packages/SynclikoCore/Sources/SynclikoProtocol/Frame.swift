import Foundation

/// Frame type identifiers. See PROTOCOL.md §3.
public enum FrameType: UInt8, Sendable, CaseIterable {
    case hello = 0x01
    case pairRequest = 0x02
    case pairResult = 0x03
    case clip = 0x10
    case clipChunk = 0x11
    case ack = 0x12
    case ping = 0x20
    case pong = 0x21
    case requestLatest = 0x30
    case endpoints = 0x31
    case error = 0x7F
}

/// A raw wire frame: `[type u8][headerLength u32][payloadLength u32][header][payload]`.
public struct Frame: Sendable, Equatable {
    public static let prefixLength = 9
    public static let maxHeaderLength = 65_536
    public static let maxPayloadLength = 1_048_576

    public var rawType: UInt8
    public var header: Data
    public var payload: Data

    public init(rawType: UInt8, header: Data, payload: Data = Data()) {
        self.rawType = rawType
        self.header = header
        self.payload = payload
    }

    public var type: FrameType? { FrameType(rawValue: rawType) }

    public func encoded() -> Data {
        var out = Data(capacity: Self.prefixLength + header.count + payload.count)
        out.append(rawType)
        out.appendBigEndian(UInt32(header.count))
        out.appendBigEndian(UInt32(payload.count))
        out.append(header)
        out.append(payload)
        return out
    }
}

public enum FrameError: Error, Equatable, Sendable {
    case headerTooLarge(Int)
    case payloadTooLarge(Int)
}

/// Incremental decoder for a TCP byte stream. Feed it arbitrary slices; it returns complete frames.
public struct FrameDecoder: Sendable {
    private var buffer = Data()

    public init() {}

    public var bufferedByteCount: Int { buffer.count }

    public mutating func append(_ bytes: Data) throws -> [Frame] {
        buffer.append(bytes)
        var frames: [Frame] = []
        while true {
            guard buffer.count >= Frame.prefixLength else { break }
            let base = buffer.startIndex
            let headerLength = Int(buffer.readBigEndianUInt32(at: base + 1))
            let payloadLength = Int(buffer.readBigEndianUInt32(at: base + 5))
            guard headerLength <= Frame.maxHeaderLength else { throw FrameError.headerTooLarge(headerLength) }
            guard payloadLength <= Frame.maxPayloadLength else { throw FrameError.payloadTooLarge(payloadLength) }
            let total = Frame.prefixLength + headerLength + payloadLength
            guard buffer.count >= total else { break }
            let headerStart = base + Frame.prefixLength
            let payloadStart = headerStart + headerLength
            let frame = Frame(
                rawType: buffer[base],
                header: Data(buffer[headerStart..<payloadStart]),
                payload: Data(buffer[payloadStart..<(payloadStart + payloadLength)])
            )
            frames.append(frame)
            buffer = Data(buffer[(base + total)...])
        }
        return frames
    }
}

extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        var be = value.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    func readBigEndianUInt32(at index: Index) -> UInt32 {
        var value: UInt32 = 0
        for i in 0..<4 { value = (value << 8) | UInt32(self[index + i]) }
        return value
    }
}
