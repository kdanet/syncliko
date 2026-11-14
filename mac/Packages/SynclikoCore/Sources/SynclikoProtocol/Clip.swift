import CryptoKit
import Foundation

/// A complete clipboard item (header + full content).
public struct Clip: Sendable, Equatable, Identifiable {
    public var header: ClipHeader
    public var content: Data

    public var id: String { header.id }
    public var kind: ClipKind { header.kind }
    public var createdAt: Date { Date(timeIntervalSince1970: TimeInterval(header.createdAt) / 1000) }
    public var text: String? { header.kind == .image ? nil : String(data: content, encoding: .utf8) }

    public init(header: ClipHeader, content: Data) {
        self.header = header
        self.content = content
    }

    public static func make(
        kind: ClipKind,
        mime: String,
        content: Data,
        origin: String,
        sensitive: Bool = false,
        id: String = UUID().uuidString,
        createdAt: Date = Date()
    ) -> Clip {
        Clip(
            header: ClipHeader(
                id: id,
                origin: origin,
                createdAt: Int64((createdAt.timeIntervalSince1970 * 1000).rounded()),
                kind: kind,
                mime: mime,
                size: content.count,
                sha256: content.sha256Hex,
                sensitive: sensitive
            ),
            content: content
        )
    }

    public static func text(_ string: String, origin: String, sensitive: Bool = false) -> Clip {
        let isURL = string.isSingleWebURL
        return make(
            kind: isURL ? .url : .text,
            mime: isURL ? "text/uri-list" : "text/plain;charset=utf-8",
            content: Data(string.utf8),
            origin: origin,
            sensitive: sensitive
        )
    }

    /// Splits the clip into a `clip` message followed by `clipChunk` messages (PROTOCOL.md §4).
    public func messages(chunkSize: Int = SynclikoProtocol.chunkSize) -> [Message] {
        var result: [Message] = []
        var offset = 0
        repeat {
            let end = min(offset + chunkSize, content.count)
            let slice = Data(content[content.startIndex + offset ..< content.startIndex + end])
            result.append(offset == 0 ? .clip(header, slice) : .clipChunk(ClipChunk(id: header.id, offset: offset), slice))
            offset = end
        } while offset < content.count
        return result
    }
}

public enum ClipAssemblyError: Error, Equatable, Sendable {
    case tooLarge(Int)
    case unexpectedChunk(id: String, offset: Int)
    case hashMismatch(id: String)
}

/// Reassembles chunked clips from incoming messages.
public struct ClipAssembler: Sendable {
    private struct Partial: Sendable {
        var header: ClipHeader
        var data: Data
        var startedAt: Date
    }

    private var partials: [String: Partial] = [:]
    public var timeout: TimeInterval = 60

    public init() {}

    /// Feed a `clip` header frame. Returns a complete clip if it fits in one frame.
    /// At most this many clips may be in flight per connection; older partials are dropped.
    public static let maxPartials = 2

    public mutating func begin(_ header: ClipHeader, firstChunk: Data, now: Date = Date()) throws -> Clip? {
        guard header.size >= 0, header.size <= SynclikoProtocol.maxClipSize else { throw ClipAssemblyError.tooLarge(header.size) }
        guard firstChunk.count <= min(header.size, SynclikoProtocol.chunkSize), header.id.count <= 64 else {
            throw ClipAssemblyError.unexpectedChunk(id: header.id, offset: 0)
        }
        expire(now: now)
        while partials.count >= Self.maxPartials, let oldest = partials.min(by: { $0.value.startedAt < $1.value.startedAt }) {
            partials[oldest.key] = nil
        }
        var partial = Partial(header: header, data: Data(), startedAt: now)
        // Don't trust the claimed size for allocation; grow as data actually arrives.
        partial.data.reserveCapacity(min(header.size, SynclikoProtocol.chunkSize))
        partial.data.append(firstChunk)
        return try finishIfComplete(partial)
    }

    public mutating func append(_ chunk: ClipChunk, data: Data) throws -> Clip? {
        guard var partial = partials[chunk.id], partial.data.count == chunk.offset,
              data.count <= SynclikoProtocol.chunkSize, partial.data.count + data.count <= partial.header.size else {
            partials[chunk.id] = nil
            throw ClipAssemblyError.unexpectedChunk(id: chunk.id, offset: chunk.offset)
        }
        partial.data.append(data)
        return try finishIfComplete(partial)
    }

    private mutating func finishIfComplete(_ partial: Partial) throws -> Clip? {
        let id = partial.header.id
        if partial.data.count < partial.header.size {
            partials[id] = partial
            return nil
        }
        partials[id] = nil
        guard partial.data.count == partial.header.size, partial.data.sha256Hex == partial.header.sha256 else {
            throw ClipAssemblyError.hashMismatch(id: id)
        }
        return Clip(header: partial.header, content: partial.data)
    }

    private mutating func expire(now: Date) {
        partials = partials.filter { now.timeIntervalSince($0.value.startedAt) < timeout }
    }
}

extension Data {
    public var sha256Hex: String {
        SHA256.hash(data: self).map { String(format: "%02x", $0) }.joined()
    }
}

extension String {
    /// True when the trimmed string is exactly one http(s) URL.
    var isSingleWebURL: Bool {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains(where: \.isWhitespace),
              let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host != nil
        else { return false }
        return true
    }
}
