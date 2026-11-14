import Foundation
import Testing
@testable import SynclikoProtocol

@Suite struct FramingTests {
    @Test func roundTripsEveryMessage() throws {
        for (name, message) in TestVectors.messages {
            let frame = SynclikoProtocol.encode(message)
            var decoder = FrameDecoder()
            let frames = try decoder.append(frame.encoded())
            #expect(frames == [frame], "\(name)")
            #expect(try SynclikoProtocol.decode(frames[0]) == message, "\(name)")
        }
    }

    @Test func decodesByteByByte() throws {
        let stream = TestVectors.messages.map { SynclikoProtocol.encode($0.message).encoded() }.reduce(Data(), +)
        var decoder = FrameDecoder()
        var frames: [Frame] = []
        for byte in stream { frames += try decoder.append(Data([byte])) }
        #expect(frames.count == TestVectors.messages.count)
        #expect(decoder.bufferedByteCount == 0)
    }

    @Test func rejectsOversizedPayload() {
        var bad = Data([0x10])
        bad.appendBigEndian(2)
        bad.appendBigEndian(UInt32(Frame.maxPayloadLength + 1))
        var decoder = FrameDecoder()
        #expect(throws: FrameError.payloadTooLarge(Frame.maxPayloadLength + 1)) { try decoder.append(bad) }
    }

    @Test func unknownTypeIsReported() {
        let frame = Frame(rawType: 0x42, header: Data("{}".utf8))
        #expect(throws: MessageDecodingError.unknownType(0x42)) { try SynclikoProtocol.decode(frame) }
    }

    @Test func ignoresUnknownHeaderKeys() throws {
        let frame = Frame(rawType: FrameType.ack.rawValue, header: Data(#"{"id":"x","future":true}"#.utf8))
        #expect(try SynclikoProtocol.decode(frame) == .ack(Ack(id: "x")))
    }
}

@Suite struct ClipTests {
    @Test func detectsURLs() {
        #expect(Clip.text("https://example.com/a?b=c", origin: "x").kind == .url)
        #expect(Clip.text("  https://example.com  ", origin: "x").kind == .url)
        #expect(Clip.text("see https://example.com", origin: "x").kind == .text)
        #expect(Clip.text("hello", origin: "x").kind == .text)
    }

    @Test func chunksAndReassemblesLargeContent() throws {
        let content = Data((0..<(SynclikoProtocol.chunkSize * 2 + 1234)).map { UInt8($0 % 251) })
        let clip = Clip.make(kind: .image, mime: "image/png", content: content, origin: "mac-1")
        let messages = clip.messages()
        #expect(messages.count == 3)

        var assembler = ClipAssembler()
        var result: Clip?
        for message in messages {
            switch message {
            case .clip(let h, let d): result = try assembler.begin(h, firstChunk: d)
            case .clipChunk(let c, let d): result = try assembler.append(c, data: d)
            default: Issue.record("unexpected message")
            }
        }
        #expect(result == clip)
    }

    @Test func emptyClipIsOneFrame() throws {
        let clip = Clip.text("", origin: "x")
        #expect(clip.messages().count == 1)
        var assembler = ClipAssembler()
        guard case .clip(let h, let d) = clip.messages()[0] else { return }
        #expect(try assembler.begin(h, firstChunk: d) == clip)
    }

    @Test func detectsCorruption() throws {
        var clip = Clip.text("hello", origin: "x")
        clip.content = Data("hellO".utf8)
        var assembler = ClipAssembler()
        #expect(throws: ClipAssemblyError.hashMismatch(id: clip.id)) {
            try assembler.begin(clip.header, firstChunk: clip.content)
        }
    }

    @Test func rejectsOutOfOrderChunk() throws {
        let content = Data(count: SynclikoProtocol.chunkSize + 10)
        let clip = Clip.make(kind: .image, mime: "image/png", content: content, origin: "x")
        var assembler = ClipAssembler()
        guard case .clip(let h, let d) = clip.messages()[0] else { return }
        _ = try assembler.begin(h, firstChunk: d)
        #expect(throws: ClipAssemblyError.self) { try assembler.append(ClipChunk(id: clip.id, offset: 5), data: Data(count: 10)) }
    }
}

@Suite struct PairingTests {
    @Test func inviteRoundTrips() throws {
        let invite = TestVectors.pairingInvite
        #expect(PairingInvite(url: invite.url) == invite)
    }

    @Test func rejectsBadInvites() {
        #expect(PairingInvite(url: URL(string: "syncliko://pair?v=1&id=x")!) == nil)
        #expect(PairingInvite(url: URL(string: "https://pair?v=1")!) == nil)
    }

    @Test func pairingCodeIsStable() {
        let mac = Data((0..<32).map { UInt8($0) })
        let phone = Data((0..<32).map { UInt8(255 - $0) })
        let code = PairingCode.make(macFingerprint: mac, phoneFingerprint: phone)
        #expect(code.count == 7)
        #expect(code == PairingCode.make(macFingerprint: mac, phoneFingerprint: phone))
        #expect(code != PairingCode.make(macFingerprint: phone, phoneFingerprint: mac))
        print("pairing code vector:", code)
    }

    @Test func rejectsLyingSizes() {
        var assembler = ClipAssembler()
        let header = ClipHeader(id: "x", origin: "o", createdAt: 0, kind: .text, mime: "text/plain", size: -1, sha256: "", sensitive: false)
        #expect(throws: ClipAssemblyError.self) { try assembler.begin(header, firstChunk: Data()) }
        var big = header
        big.size = 4
        #expect(throws: ClipAssemblyError.self) { try assembler.begin(big, firstChunk: Data(count: 10)) }
    }

    @Test func base64URL() {
        let data = Data([0xFB, 0xFF, 0xFE])
        #expect(data.base64URLEncoded == "-__-")
        #expect(Data(base64URLEncoded: "-__-") == data)
    }
}

/// Verifies the checked-in shared vectors (the Android test suite reads the same file).
@Suite(.serialized) struct SharedVectorTests {
    static let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("protocol/vectors.json")

    @Test func vectorsFileMatchesImplementation() throws {
        let expected = try TestVectors.json()
        if ProcessInfo.processInfo.environment["SYNCLIKO_WRITE_VECTORS"] == "1" {
            try expected.write(to: Self.url)
        }
        let onDisk = try Data(contentsOf: Self.url)
        let a = try JSONSerialization.jsonObject(with: onDisk) as! NSDictionary
        let b = try JSONSerialization.jsonObject(with: expected) as! NSDictionary
        #expect(a == b, "protocol/vectors.json is stale; rerun with SYNCLIKO_WRITE_VECTORS=1")
    }

    @Test func vectorsDecode() throws {
        let doc = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.url)) as! [String: Any]
        for entry in doc["frames"] as! [[String: Any]] {
            let bytes = Data(hexString: entry["hex"] as! String)!
            var decoder = FrameDecoder()
            let frames = try decoder.append(bytes)
            #expect(frames.count == 1)
            let message = try SynclikoProtocol.decode(frames[0])
            #expect(SynclikoProtocol.encode(message).encoded() == bytes, "\(entry["name"]!)")
        }
    }
}
