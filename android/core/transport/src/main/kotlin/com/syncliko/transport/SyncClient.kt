package com.syncliko.transport

import com.syncliko.protocol.Clip
import com.syncliko.protocol.Endpoint
import com.syncliko.protocol.Hello
import com.syncliko.protocol.Message
import com.syncliko.protocol.PairRequest
import com.syncliko.protocol.PairResult
import com.syncliko.protocol.PairingInvite
import com.syncliko.protocol.ProtocolError
import com.syncliko.protocol.base64UrlEncode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlin.random.Random

data class HostPort(val host: String, val port: Int, val kind: Endpoint.Kind) {
    override fun toString() = "$host:$port ($kind)"
}

/** A paired Mac as remembered by the phone. */
data class MacTarget(
    val deviceId: String,
    val name: String,
    val fingerprint: ByteArray,
    val endpoints: List<HostPort>,
) {
    override fun equals(other: Any?) =
        other is MacTarget && deviceId == other.deviceId && name == other.name && fingerprint.contentEquals(other.fingerprint) && endpoints == other.endpoints
    override fun hashCode() = deviceId.hashCode()
}

sealed interface PairOutcome {
    data class Paired(val target: MacTarget) : PairOutcome
    data class Failed(val reason: String) : PairOutcome
}

/**
 * Keeps one connection to the paired Mac alive: races all known endpoints ("happy eyeballs"),
 * reconnects with exponential backoff, and exposes received clips as flows.
 */
class SyncClient(
    private val identity: DeviceIdentity,
    private val hello: Hello,
    private val scope: CoroutineScope,
    /** Extra endpoints found at connect time, e.g. Bonjour/NSD results. */
    private val discover: suspend () -> List<HostPort> = { emptyList() },
    /** Clock that keeps running in deep sleep (Android: SystemClock.elapsedRealtime). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called on every incoming frame (lets the app hold a short wake lock while data arrives). */
    private val onFrame: () -> Unit = {},
) {
    sealed interface State {
        data object Idle : State
        data class Connecting(val attempt: Int) : State
        data class Connected(val peer: Hello, val endpoint: HostPort) : State
        data class Waiting(val retryInMs: Long, val error: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _clips = MutableSharedFlow<Clip>(extraBufferCapacity = 16)
    val clips: SharedFlow<Clip> = _clips.asSharedFlow()

    private val _endpoints = MutableSharedFlow<List<HostPort>>(replay = 1, extraBufferCapacity = 4)
    /** Endpoint list pushed by the Mac (persist it so Tailscale works without Bonjour). */
    val endpoints: SharedFlow<List<HostPort>> = _endpoints.asSharedFlow()

    private val _undelivered = MutableSharedFlow<Clip>(extraBufferCapacity = 4)
    /** A clip we sent that the Mac never acknowledged before the connection dropped (re-send it). */
    val undelivered: SharedFlow<Clip> = _undelivered.asSharedFlow()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Clip>()

    private val _latestRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** The Mac asked for our most recent clip (sent after it reconnects). */
    val latestRequests: SharedFlow<Unit> = _latestRequests.asSharedFlow()

    @Volatile private var session: Session? = null
    @Volatile private var target: MacTarget? = null
    @Volatile private var lastGood: HostPort? = null
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(target: MacTarget) {
        this.target = target
        if (loop?.isActive == true) {
            reconnectNow()
            return
        }
        loop = scope.launch(Dispatchers.IO) { runLoop() }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        session?.close("stopped")
        session = null
        _state.value = State.Idle
    }

    /** Drop the current connection (e.g. its network went away); the loop reconnects right away. */
    fun dropConnection(reason: String) {
        session?.close(reason)
        reconnectNow()
    }

    /**
     * Verify the connection after the phone wakes up, the network changes or before sending: if nothing
     * arrived for [staleMs], ping and require a pong, otherwise reconnect. A half-open socket after a
     * Wi-Fi roam or the Mac sleeping can otherwise look "connected" for minutes.
     */
    suspend fun ensureAlive(staleMs: Long = 60_000) {
        val s = session ?: run { reconnectNow(); return }
        if (clock() - s.lastReceivedAt > staleMs && !s.probe()) reconnectNow()
    }

    /** Skip the backoff delay (network changed, app opened, …). */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    /** Sends a clip if connected. Returns false when offline. */
    fun send(clip: Clip): Boolean {
        val s = session ?: return false
        if (_state.value !is State.Connected) return false
        if (!s.send(clip)) return false
        inFlight[clip.id] = clip
        return true
    }

    fun requestLatest() {
        session?.let { s -> runCatching { s.send(Message.RequestLatest) } }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var backoff = 500L
        while (scope.isActive) {
            val target = target ?: return
            attempt++
            _state.value = State.Connecting(attempt)
            var error: String? = null
            try {
                val candidates = (listOfNotNull(lastGood) + discover() + target.endpoints).distinctBy { it.host to it.port }
                val (socket, endpoint) = race(scope, identity, candidates, target.fingerprint)
                val s = Session(socket, scope, clock, onFrame)
                session = s
                s.send(Message.HelloMsg(hello))
                val (connected, closeReason) = runSession(s, endpoint)
                if (connected) {
                    lastGood = endpoint
                    attempt = 0
                    backoff = 500L
                } else {
                    // Closed before the hello exchange: with TLS 1.3 the Mac rejects an unknown client
                    // certificate only after the handshake, so its alert surfaces here.
                    error = closeReason ?: "closed before hello"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e.isPinMismatch()) "This Mac's identity changed. Pair again." else (e.message ?: e.javaClass.simpleName)
            } finally {
                session?.close()
                session = null
                // Anything the Mac didn't acknowledge goes back to the app to be re-sent.
                inFlight.values.maxByOrNull { it.header.createdAt }?.let { _undelivered.tryEmit(it) }
                inFlight.clear()
            }
            // Failures that can't fix themselves (Mac forgot us, identity changed) are retried rarely,
            // instead of waking the radio every 30 s; the user can still reconnect from the app.
            val fatal = error?.lowercase()?.let { "identity changed" in it || "unauthorized" in it || "certificate" in it || "bad_certificate" in it } == true
            if (fatal) backoff = 15 * 60_000L
            val jitter = (backoff * 0.2 * (Random.nextDouble() * 2 - 1)).toLong()
            val waitMs = backoff + jitter
            _state.value = State.Waiting(waitMs, error)
            withTimeoutOrNull(waitMs) { wake.receive() }
            if (!fatal) backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** Pumps events until the session closes. Returns whether the hello exchange completed, and why it closed. */
    private suspend fun runSession(s: Session, endpoint: HostPort): Pair<Boolean, String?> {
        var connected = false
        var closeReason: String? = null
        s.events.collect { event ->
            when (event) {
                is Session.Event.ClipReceived -> _clips.emit(event.clip)
                is Session.Event.Received -> when (val m = event.message) {
                    is Message.HelloMsg -> {
                        if (m.hello.protocolVersion != com.syncliko.protocol.SynclikoProtocol.VERSION) {
                            s.close("incompatible protocol version ${m.hello.protocolVersion}")
                            return@collect
                        }
                        connected = true
                        _state.value = State.Connected(m.hello, endpoint)
                        // Fetch a clip copied on the Mac while we were offline.
                        runCatching { s.send(Message.RequestLatest) }
                    }
                    is Message.EndpointsMsg -> _endpoints.emit(m.endpoints.endpoints.map { HostPort(it.host, it.port, it.kind) })
                    Message.RequestLatest -> _latestRequests.emit(Unit)
                    is Message.AckMsg -> inFlight.remove(m.ack.id)
                    is Message.ErrorMsg -> if (m.error.code == ProtocolError.Code.UNAUTHORIZED) s.close("unauthorized: Mac doesn't trust this certificate")
                    else -> Unit
                }
                is Session.Event.Closed -> closeReason = event.reason
            }
        }
        return connected to closeReason
    }

    companion object {
        /**
         * Pairs with the Mac from a scanned invite. Suspends until the user confirms on the Mac
         * (up to [confirmTimeoutMs]).
         */
        suspend fun pair(
            identity: DeviceIdentity,
            hello: Hello,
            invite: PairingInvite,
            scope: CoroutineScope,
            confirmTimeoutMs: Long = 120_000,
            onWaitingForConfirmation: () -> Unit = {},
        ): PairOutcome {
            val candidates = invite.hosts.map { HostPort(it, invite.port, if (it.startsWith("100.") || it.endsWith(".ts.net")) Endpoint.Kind.TAILSCALE else Endpoint.Kind.LAN) }
            val (socket, endpoint) = try {
                race(scope, identity, candidates, invite.fingerprint)
            } catch (e: Exception) {
                return PairOutcome.Failed(if (e.isPinMismatch()) "The Mac's certificate doesn't match the QR code." else "Can't reach the Mac: ${e.message}")
            }
            val session = Session(socket, scope)
            try {
                session.send(Message.PairRequestMsg(PairRequest(hello.deviceId, hello.deviceName, hello.platform, base64UrlEncode(invite.token))))
                onWaitingForConfirmation()
                val reply = withTimeout(confirmTimeoutMs) {
                    session.events.mapNotNull { event ->
                        when (event) {
                            is Session.Event.Received -> when (val m = event.message) {
                                is Message.PairResultMsg -> m.result
                                is Message.ErrorMsg -> PairResult(false, "", "", m.error.message.ifEmpty { m.error.code.name })
                                else -> null
                            }
                            is Session.Event.Closed -> PairResult(false, "", "", event.reason ?: "connection closed")
                            else -> null
                        }
                    }.first()
                }
                if (!reply.accepted) return PairOutcome.Failed(reply.reason ?: "rejected")
                val hosts = (listOf(endpoint) + candidates).distinct()
                return PairOutcome.Paired(MacTarget(invite.deviceId, reply.deviceName.ifEmpty { invite.deviceName }, invite.fingerprint, hosts))
            } catch (e: Exception) {
                return PairOutcome.Failed(e.message ?: "pairing failed")
            } finally {
                session.close("pairing done")
            }
        }

        /** Connects to all candidates (staggered by 250 ms); the first completed TLS handshake wins. */
        internal suspend fun race(
            scope: CoroutineScope,
            identity: DeviceIdentity,
            candidates: List<HostPort>,
            fingerprint: ByteArray,
        ): Pair<SSLSocket, HostPort> {
            if (candidates.isEmpty()) throw IOException("no known address for the Mac")
            val winner = CompletableDeferred<Pair<SSLSocket, HostPort>>()
            val remaining = AtomicInteger(candidates.size)
            val errors = ConcurrentLinkedQueue<Exception>()
            candidates.forEachIndexed { index, candidate ->
                scope.launch(Dispatchers.IO) {
                    delay(index * 250L)
                    if (winner.isCompleted) return@launch
                    try {
                        val socket = Tls.connect(identity, candidate.host, candidate.port, fingerprint)
                        if (!winner.complete(socket to candidate)) socket.close()
                    } catch (e: Exception) {
                        errors += e
                        if (remaining.decrementAndGet() == 0) {
                            winner.completeExceptionally(errors.firstOrNull { it.isPinMismatch() } ?: IOException(errors.joinToString("; ") { it.message ?: it.javaClass.simpleName }))
                        }
                    }
                }
            }
            try {
                return winner.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // We were cancelled after a winner connected: don't leak its socket.
                if (winner.isCompleted && !winner.isCancelled) runCatching { winner.getCompleted().first.close() }
                throw e
            }
        }
    }
}

/** True if this exception (or any cause, e.g. inside SSLHandshakeException) is a pin mismatch. */
fun Throwable.isPinMismatch(): Boolean = generateSequence(this) { it.cause }.any { it is PinMismatchException }
