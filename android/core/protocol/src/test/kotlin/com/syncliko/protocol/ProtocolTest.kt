package com.syncliko.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the shared vectors produced by the Swift implementation (protocol/vectors.json). */
class SharedVectorsTest {
    private val doc: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("syncliko.vectors") ?: "../../../protocol/vectors.json").readText(),
    ).jsonObject

    @Test
    fun framesDecodeAndReencodeByteForByte() {
        val frames = doc.getValue("frames").jsonArray
        assertTrue(frames.size >= 10)
        for (entry in frames) {
            val obj = entry.jsonObject
            val name = obj.getValue("name").jsonPrimitive.content
            val bytes = obj.getValue("hex").jsonPrimitive.content.hexToBytes()
            val frame = Frame.read(ByteArrayInputStream(bytes))
            assertEquals(obj.getValue("type").jsonPrimitive.int, frame.rawType, name)
            val message = SynclikoProtocol.decode(frame)
            assertContentEquals(bytes, SynclikoProtocol.encode(message).encode(), "re-encoding $name")
        }
    }

    @Test
    fun clipVectorHasValidHash() {
        val clip = doc.getValue("frames").jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "clipText" }
        val message = SynclikoProtocol.decode(Frame.read(ByteArrayInputStream(clip["hex"]!!.jsonPrimitive.content.hexToBytes())))
        message as Message.ClipMsg
        assertEquals("Привет, Syncliko! 👋", message.data.decodeToString())
        assertEquals(message.header.sha256, message.data.sha256Hex())
    }

    @Test
    fun pairingInvitesParse() {
        for (entry in doc.getValue("pairingInvites").jsonArray) {
            val obj = entry.jsonObject
            val invite = assertNotNull(PairingInvite.parse(obj.getValue("url").jsonPrimitive.content))
            assertEquals(obj.getValue("deviceId").jsonPrimitive.content, invite.deviceId)
            assertEquals(obj.getValue("deviceName").jsonPrimitive.content, invite.deviceName)
            assertContentEquals(obj.getValue("fingerprintHex").jsonPrimitive.content.hexToBytes(), invite.fingerprint)
            assertContentEquals(obj.getValue("tokenHex").jsonPrimitive.content.hexToBytes(), invite.token)
            assertEquals(obj.getValue("port").jsonPrimitive.int, invite.port)
            assertEquals(obj.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }, invite.hosts)
        }
    }
}

class FramingTest {
    @Test
    fun decoderHandlesByteByByteStream() {
        val messages = listOf(
            Message.PingMsg(Ping(1)),
            Message.RequestLatest,
            Message.AckMsg(Ack("x")),
        )
        val stream = messages.flatMap { SynclikoProtocol.encode(it).encode().toList() }
        val decoder = FrameDecoder()
        val frames = stream.flatMap { decoder.append(byteArrayOf(it)) }
        assertEquals(messages, frames.map(SynclikoProtocol::decode))
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun rejectsOversizedPayload() {
        val bad = byteArrayOf(0x10, 0, 0, 0, 2, 0x7F, 0, 0, 0)
        assertFailsWith<FrameException> { Frame.read(ByteArrayInputStream(bad)) }
    }

    @Test
    fun unknownTypeAndExtraKeys() {
        assertFailsWith<UnknownFrameTypeException> { SynclikoProtocol.decode(Frame(0x42, "{}".encodeToByteArray())) }
        val ack = SynclikoProtocol.decode(Frame(0x12, """{"id":"x","future":1}""".encodeToByteArray()))
        assertEquals(Message.AckMsg(Ack("x")), ack)
    }

    @Test
    fun omitsNullReason() {
        val frame = SynclikoProtocol.encode(Message.PairResultMsg(PairResult(true, "a", "b")))
        assertEquals("""{"accepted":true,"deviceId":"a","deviceName":"b"}""", frame.header.decodeToString())
    }
}

class ClipTest {
    @Test
    fun detectsUrls() {
        assertEquals(ClipKind.URL, Clip.text("https://example.com/a?b=c", "x").kind)
        assertEquals(ClipKind.URL, Clip.text("  https://example.com  ", "x").kind)
        assertEquals(ClipKind.TEXT, Clip.text("see https://example.com", "x").kind)
    }

    @Test
    fun chunksAndReassembles() {
        val content = ByteArray(SynclikoProtocol.CHUNK_SIZE * 2 + 1234) { (it % 251).toByte() }
        val clip = Clip.make(ClipKind.IMAGE, "image/png", content, "x")
        val messages = clip.messages()
        assertEquals(3, messages.size)
        val assembler = ClipAssembler()
        var result: Clip? = null
        for (m in messages) {
            result = when (m) {
                is Message.ClipMsg -> assembler.begin(m.header, m.data)
                is Message.ClipChunkMsg -> assembler.append(m.chunk, m.data)
                else -> error("unexpected")
            }
        }
        assertEquals(clip, result)
    }

    @Test
    fun detectsCorruption() {
        val clip = Clip.text("hello", "x")
        assertFailsWith<ClipAssemblyException> { ClipAssembler().begin(clip.header, "hellO".encodeToByteArray()) }
    }

    @Test
    fun pairingCodeMatchesSwift() {
        val mac = ByteArray(32) { it.toByte() }
        val phone = ByteArray(32) { (255 - it).toByte() }
        // Same vector as the Swift test (PairingCode.make → "646 654").
        assertEquals("646 654", PairingCode.make(mac, phone))
    }

    @Test
    fun rejectsLyingClips() {
        val header = ClipHeader(0, "x", ClipKind.TEXT, "text/plain", "o", false, "", -1)
        assertFailsWith<ClipAssemblyException> { ClipAssembler().begin(header, ByteArray(0)) }
        assertFailsWith<ClipAssemblyException> { ClipAssembler().begin(header.copy(size = 4), ByteArray(10)) }
        assertFailsWith<ClipAssemblyException> { ClipAssembler().begin(header.copy(size = 1, id = "../../x"), ByteArray(1)) }
    }

    @Test
    fun rejectsBadInvite() {
        assertNull(PairingInvite.parse("syncliko://pair?v=1&id=x"))
        assertNull(PairingInvite.parse("https://pair?v=1"))
    }
}
