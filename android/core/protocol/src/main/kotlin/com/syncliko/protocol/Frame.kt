package com.syncliko.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/** Frame type identifiers. See PROTOCOL.md §3. */
enum class FrameType(val code: Int) {
    HELLO(0x01),
    PAIR_REQUEST(0x02),
    PAIR_RESULT(0x03),
    CLIP(0x10),
    CLIP_CHUNK(0x11),
    ACK(0x12),
    PING(0x20),
    PONG(0x21),
    REQUEST_LATEST(0x30),
    ENDPOINTS(0x31),
    ERROR(0x7F);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): FrameType? = byCode[code]
    }
}

/** A raw wire frame: `[type u8][headerLength u32][payloadLength u32][header][payload]`. */
class Frame(val rawType: Int, val header: ByteArray, val payload: ByteArray = EMPTY) {
    val type: FrameType? get() = FrameType.of(rawType)

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(PREFIX_LENGTH + header.size + payload.size)
        buffer.put(rawType.toByte())
        buffer.putInt(header.size)
        buffer.putInt(payload.size)
        buffer.put(header)
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && rawType == other.rawType && header.contentEquals(other.header) && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * rawType + header.contentHashCode()) + payload.contentHashCode()

    companion object {
        const val PREFIX_LENGTH = 9
        const val MAX_HEADER_LENGTH = 65_536
        const val MAX_PAYLOAD_LENGTH = 1_048_576
        val EMPTY = ByteArray(0)

        /** Reads one frame from a blocking stream. Throws [EOFException] at end of stream. */
        fun read(input: InputStream): Frame {
            val data = if (input is DataInputStream) input else DataInputStream(input)
            val type = data.read()
            if (type < 0) throw EOFException()
            val headerLength = data.readInt()
            val payloadLength = data.readInt()
            if (headerLength !in 0..MAX_HEADER_LENGTH) throw FrameException("header too large: $headerLength")
            if (payloadLength !in 0..MAX_PAYLOAD_LENGTH) throw FrameException("payload too large: $payloadLength")
            val header = ByteArray(headerLength).also { data.readFully(it) }
            val payload = if (payloadLength == 0) EMPTY else ByteArray(payloadLength).also { data.readFully(it) }
            return Frame(type, header, payload)
        }
    }
}

class FrameException(message: String) : Exception(message)

/** Incremental decoder for arbitrary byte slices (mirrors the Swift `FrameDecoder`). */
class FrameDecoder {
    private var buffer = ByteArrayOutputStream()

    fun append(bytes: ByteArray): List<Frame> {
        buffer.write(bytes)
        val all = buffer.toByteArray()
        val frames = mutableListOf<Frame>()
        var offset = 0
        while (all.size - offset >= Frame.PREFIX_LENGTH) {
            val bb = ByteBuffer.wrap(all, offset, all.size - offset)
            val type = bb.get().toInt() and 0xFF
            val headerLength = bb.int
            val payloadLength = bb.int
            if (headerLength !in 0..Frame.MAX_HEADER_LENGTH) throw FrameException("header too large: $headerLength")
            if (payloadLength !in 0..Frame.MAX_PAYLOAD_LENGTH) throw FrameException("payload too large: $payloadLength")
            val total = Frame.PREFIX_LENGTH + headerLength + payloadLength
            if (all.size - offset < total) break
            val headerStart = offset + Frame.PREFIX_LENGTH
            frames += Frame(
                type,
                all.copyOfRange(headerStart, headerStart + headerLength),
                all.copyOfRange(headerStart + headerLength, offset + total),
            )
            offset += total
        }
        buffer = ByteArrayOutputStream().also { it.write(all, offset, all.size - offset) }
        return frames
    }

    val bufferedByteCount: Int get() = buffer.size()
}
