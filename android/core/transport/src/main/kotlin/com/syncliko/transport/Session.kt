package com.syncliko.transport

import com.syncliko.protocol.Ack
import com.syncliko.protocol.Clip
import com.syncliko.protocol.ClipAssembler
import com.syncliko.protocol.ClipAssemblyException
import com.syncliko.protocol.Frame
import com.syncliko.protocol.Message
import com.syncliko.protocol.Ping
import com.syncliko.protocol.ProtocolError
import com.syncliko.protocol.SynclikoProtocol
import com.syncliko.protocol.UnknownFrameTypeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/**
 * One established TLS connection: framing, clip reassembly, ping/pong and acks.
 * Higher-level events arrive on [events]; the channel closes when the connection ends.
 */
class Session(
    private val socket: SSLSocket,
    scope: CoroutineScope,
    /** Monotonic clock that keeps counting in deep sleep (Android: SystemClock.elapsedRealtime). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called for every incoming frame (e.g. to hold a short wake lock while data arrives). */
    private val onFrame: () -> Unit = {},
) {
    sealed interface Event {
        data class Received(val message: Message) : Event
        data class ClipReceived(val clip: Clip) : Event
        data class Closed(val reason: String?) : Event
    }

    val host: String = socket.inetAddress.hostAddress
    private val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
    private val input = DataInputStream(BufferedInputStream(socket.inputStream, 64 * 1024))
    private val closed = AtomicBoolean(false)
    private val channel = Channel<Event>(Channel.UNLIMITED)
    private val assembler = ClipAssembler()

    /** Each element is written atomically: a clip's frames never interleave with another clip's. */
    private val outgoing = Channel<List<ByteArray>>(Channel.UNLIMITED)
    private val reader: Job
    private val writer: Job
    private val pinger: Job

    /** Clip id → time sent, until the peer acks it. */
    private val awaitingAck = ConcurrentHashMap<String, Long>()
    private val pendingPong = AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile var lastReceivedAt: Long = clock()
        private set

    val events: Flow<Event> = channel.receiveAsFlow()
    val isOpen: Boolean get() = !closed.get()

    init {
        reader = scope.launch(Dispatchers.IO) { readLoop() }
        writer = scope.launch(Dispatchers.IO) {
            try {
                for (frames in outgoing) {
                    for (bytes in frames) output.write(bytes)
                    if (outgoing.isEmpty) output.flush()
                }
            } catch (e: Exception) {
                close(e.message ?: "write failed")
            }
        }
        pinger = scope.launch(Dispatchers.IO) {
            var sinceLastPing = 0L
            while (isActive && isOpen) {
                delay(5_000)
                sinceLastPing += 5_000
                if (sinceLastPing >= SynclikoProtocol.PING_INTERVAL_MS) {
                    sinceLastPing = 0
                    send(Message.PingMsg(Ping(System.currentTimeMillis())))
                }
                val now = clock()
                if (awaitingAck.values.any { now - it > ACK_TIMEOUT_MS }) close("ack timeout")
            }
        }
    }

    /** Queues a message; safe from any thread, including Android's main thread. Returns false if closed. */
    fun send(message: Message): Boolean =
        isOpen && outgoing.trySend(listOf(SynclikoProtocol.encode(message).encode())).isSuccess

    fun send(clip: Clip): Boolean {
        if (!isOpen) return false
        val frames = clip.messages().map { SynclikoProtocol.encode(it).encode() }
        if (!outgoing.trySend(frames).isSuccess) return false
        awaitingAck[clip.id] = clock()
        return true
    }

    /** Ids of clips sent on this session that the peer never acknowledged. */
    fun unacknowledged(): Set<String> = awaitingAck.keys.toSet()

    /**
     * Checks the connection is really alive (after sleep, a Wi-Fi roam, …): sends a ping and waits for
     * the pong. Closes the session and returns false if it doesn't arrive in time.
     */
    suspend fun probe(timeoutMs: Long = 3_000): Boolean {
        if (!isOpen) return false
        val pong = CompletableDeferred<Unit>()
        pendingPong.set(pong)
        if (!send(Message.PingMsg(Ping(System.currentTimeMillis())))) return false
        val ok = withTimeoutOrNull(timeoutMs) { pong.await() } != null
        if (!ok) close("no pong (stale connection)")
        return ok
    }

    fun close(reason: String? = null) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        outgoing.close()
        pinger.cancel()
        pendingPong.getAndSet(null)?.cancel()
        channel.trySend(Event.Closed(reason))
        channel.close()
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val frame = Frame.read(input)
                lastReceivedAt = clock()
                onFrame()
                val message = try {
                    SynclikoProtocol.decode(frame)
                } catch (e: UnknownFrameTypeException) {
                    trySendError(ProtocolError.Code.UNKNOWN_TYPE, e.message ?: "")
                    continue
                } catch (e: Exception) {
                    trySendError(ProtocolError.Code.BAD_FRAME, e.message ?: "")
                    continue
                }
                handle(message)
            }
        } catch (e: Exception) {
            close(if (closed.get()) null else (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.PingMsg -> send(Message.PongMsg(message.ping))
            is Message.PongMsg -> pendingPong.getAndSet(null)?.complete(Unit)
            is Message.AckMsg -> {
                awaitingAck.remove(message.ack.id)
                channel.trySend(Event.Received(message))
            }
            is Message.ClipMsg -> reassemble { assembler.begin(message.header, message.data) }
            is Message.ClipChunkMsg -> reassemble { assembler.append(message.chunk, message.data) }
            else -> channel.trySend(Event.Received(message))
        }
    }

    private fun reassemble(step: () -> Clip?) {
        try {
            val clip = step() ?: return
            send(Message.AckMsg(Ack(clip.id)))
            channel.trySend(Event.ClipReceived(clip))
        } catch (e: ClipAssemblyException) {
            trySendError(ProtocolError.Code.HASH_MISMATCH, e.message ?: "")
        } catch (e: IllegalArgumentException) {
            trySendError(ProtocolError.Code.BAD_FRAME, e.message ?: "")
        }
    }

    private fun trySendError(code: ProtocolError.Code, text: String) {
        send(Message.ErrorMsg(ProtocolError(code, text)))
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 15_000L
    }
}
