package com.syncliko.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Header types. Properties are declared in alphabetical order so the encoded JSON has sorted keys,
// byte-identical to the Swift encoder (PROTOCOL.md §2) and to protocol/vectors.json.

@Serializable
enum class Platform {
    @SerialName("macos") MACOS,
    @SerialName("android") ANDROID,
}

@Serializable
data class Hello(
    val appVersion: String,
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val protocolVersion: Int = SynclikoProtocol.VERSION,
)

@Serializable
data class PairRequest(
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val token: String,
)

@Serializable
data class PairResult(
    val accepted: Boolean,
    val deviceId: String,
    val deviceName: String,
    val reason: String? = null,
)

@Serializable
enum class ClipKind {
    @SerialName("text") TEXT,
    @SerialName("url") URL,
    @SerialName("image") IMAGE,
}

@Serializable
data class ClipHeader(
    val createdAt: Long,
    val id: String,
    val kind: ClipKind,
    val mime: String,
    val origin: String,
    val sensitive: Boolean,
    val sha256: String,
    val size: Int,
)

@Serializable
data class ClipChunk(val id: String, val offset: Int)

@Serializable
data class Ack(val id: String)

@Serializable
data class Ping(val t: Long)

@Serializable
data class Endpoint(val host: String, val kind: Kind, val port: Int) {
    @Serializable
    enum class Kind {
        @SerialName("lan") LAN,
        @SerialName("tailscale") TAILSCALE,
        @SerialName("bonjour") BONJOUR,
    }
}

@Serializable
data class Endpoints(val endpoints: List<Endpoint>)

@Serializable
data class ProtocolError(val code: Code, val message: String) {
    @Serializable
    enum class Code {
        @SerialName("version") VERSION,
        @SerialName("unauthorized") UNAUTHORIZED,
        @SerialName("badFrame") BAD_FRAME,
        @SerialName("unknownType") UNKNOWN_TYPE,
        @SerialName("tooLarge") TOO_LARGE,
        @SerialName("hashMismatch") HASH_MISMATCH,
        @SerialName("pairingClosed") PAIRING_CLOSED,
        @SerialName("badToken") BAD_TOKEN,
        @SerialName("rejected") REJECTED,
    }
}

@Serializable
internal class EmptyHeader

/** A decoded, typed protocol message. */
sealed interface Message {
    val type: FrameType

    data class HelloMsg(val hello: Hello) : Message { override val type get() = FrameType.HELLO }
    data class PairRequestMsg(val request: PairRequest) : Message { override val type get() = FrameType.PAIR_REQUEST }
    data class PairResultMsg(val result: PairResult) : Message { override val type get() = FrameType.PAIR_RESULT }
    class ClipMsg(val header: ClipHeader, val data: ByteArray) : Message {
        override val type get() = FrameType.CLIP
        override fun equals(other: Any?) = other is ClipMsg && header == other.header && data.contentEquals(other.data)
        override fun hashCode() = header.hashCode() * 31 + data.contentHashCode()
    }
    class ClipChunkMsg(val chunk: ClipChunk, val data: ByteArray) : Message {
        override val type get() = FrameType.CLIP_CHUNK
        override fun equals(other: Any?) = other is ClipChunkMsg && chunk == other.chunk && data.contentEquals(other.data)
        override fun hashCode() = chunk.hashCode() * 31 + data.contentHashCode()
    }
    data class AckMsg(val ack: Ack) : Message { override val type get() = FrameType.ACK }
    data class PingMsg(val ping: Ping) : Message { override val type get() = FrameType.PING }
    data class PongMsg(val ping: Ping) : Message { override val type get() = FrameType.PONG }
    data object RequestLatest : Message { override val type get() = FrameType.REQUEST_LATEST }
    data class EndpointsMsg(val endpoints: Endpoints) : Message { override val type get() = FrameType.ENDPOINTS }
    data class ErrorMsg(val error: ProtocolError) : Message { override val type get() = FrameType.ERROR }
}

class UnknownFrameTypeException(val rawType: Int) : Exception("unknown frame type 0x${rawType.toString(16)}")
class BadHeaderException(type: FrameType, cause: Throwable) : Exception("bad $type header", cause)

object SynclikoProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47823
    const val BONJOUR_TYPE = "_syncliko._tcp"
    const val ALPN = "syncliko/1"
    const val CHUNK_SIZE = 262_144
    const val MAX_CLIP_SIZE = 20 * 1_048_576
    const val LATEST_MAX_AGE_MS = 120_000L
    const val PING_INTERVAL_MS = 45_000L
    const val IDLE_TIMEOUT_MS = 120_000L

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun <T> h(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value).encodeToByteArray()

    fun encode(message: Message): Frame {
        val t = message.type.code
        return when (message) {
            is Message.HelloMsg -> Frame(t, h(Hello.serializer(), message.hello))
            is Message.PairRequestMsg -> Frame(t, h(PairRequest.serializer(), message.request))
            is Message.PairResultMsg -> Frame(t, h(PairResult.serializer(), message.result))
            is Message.ClipMsg -> Frame(t, h(ClipHeader.serializer(), message.header), message.data)
            is Message.ClipChunkMsg -> Frame(t, h(ClipChunk.serializer(), message.chunk), message.data)
            is Message.AckMsg -> Frame(t, h(Ack.serializer(), message.ack))
            is Message.PingMsg -> Frame(t, h(Ping.serializer(), message.ping))
            is Message.PongMsg -> Frame(t, h(Ping.serializer(), message.ping))
            Message.RequestLatest -> Frame(t, h(EmptyHeader.serializer(), EmptyHeader()))
            is Message.EndpointsMsg -> Frame(t, h(Endpoints.serializer(), message.endpoints))
            is Message.ErrorMsg -> Frame(t, h(ProtocolError.serializer(), message.error))
        }
    }

    fun decode(frame: Frame): Message {
        val type = frame.type ?: throw UnknownFrameTypeException(frame.rawType)
        fun <T> d(serializer: KSerializer<T>): T =
            try {
                json.decodeFromString(serializer, frame.header.decodeToString())
            } catch (e: Exception) {
                throw BadHeaderException(type, e)
            }
        return when (type) {
            FrameType.HELLO -> Message.HelloMsg(d(Hello.serializer()))
            FrameType.PAIR_REQUEST -> Message.PairRequestMsg(d(PairRequest.serializer()))
            FrameType.PAIR_RESULT -> Message.PairResultMsg(d(PairResult.serializer()))
            FrameType.CLIP -> Message.ClipMsg(d(ClipHeader.serializer()), frame.payload)
            FrameType.CLIP_CHUNK -> Message.ClipChunkMsg(d(ClipChunk.serializer()), frame.payload)
            FrameType.ACK -> Message.AckMsg(d(Ack.serializer()))
            FrameType.PING -> Message.PingMsg(d(Ping.serializer()))
            FrameType.PONG -> Message.PongMsg(d(Ping.serializer()))
            FrameType.REQUEST_LATEST -> Message.RequestLatest
            FrameType.ENDPOINTS -> Message.EndpointsMsg(d(Endpoints.serializer()))
            FrameType.ERROR -> Message.ErrorMsg(d(ProtocolError.serializer()))
        }
    }
}
