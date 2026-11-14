package com.syncliko.android.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.syncliko.android.capture.ClipboardTileService
import com.syncliko.android.capture.CopyAccessibilityService
import com.syncliko.android.ui.MainActivity
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.syncliko.android.BuildConfig
import com.syncliko.android.R
import com.syncliko.android.capture.CaptureEngine
import com.syncliko.android.core.ClipboardBridge
import com.syncliko.android.core.HistoryStore
import com.syncliko.android.core.KeystoreIdentity
import com.syncliko.android.core.LocalNetwork
import com.syncliko.android.core.Store
import com.syncliko.protocol.Clip
import com.syncliko.protocol.Hello
import com.syncliko.protocol.PairingCode
import com.syncliko.protocol.PairingInvite
import com.syncliko.protocol.Platform
import com.syncliko.protocol.SynclikoProtocol
import com.syncliko.protocol.toHex
import com.syncliko.transport.DeviceIdentity
import com.syncliko.transport.MacTarget
import com.syncliko.transport.PairOutcome
import com.syncliko.transport.SyncClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class HistoryItem(
    val clip: Clip,
    val direction: Direction,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Direction { SENT, RECEIVED }
    val key: String get() = clip.id
}

sealed interface PairingState {
    data object Idle : PairingState
    data object Connecting : PairingState
    data class AwaitingConfirmation(val macName: String, val code: String) : PairingState
    data class Failed(val reason: String) : PairingState
    data class Done(val macName: String) : PairingState
}

enum class SendResult { SENT, QUEUED, NOTHING, DUPLICATE, PAUSED, TOO_LARGE }

/** Why the connection keeps failing, when it's something the user can fix. */
enum class ConnectionProblem { NONE, MAC_FORGOT_PHONE, MAC_IDENTITY_CHANGED }

/** App-wide sync logic. One instance, owned by [com.syncliko.android.SynclikoApp]. */
class Controller(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = Store(context)
    val clipboard = ClipboardBridge(context, store.deviceId)
    private val historyStore = HistoryStore(context)
    private val hello get() = Hello(BuildConfig.VERSION_NAME, store.deviceId, store.deviceName, Platform.ANDROID)
    private val nsd = NsdDiscovery(context) { store.pairedMac?.deviceId }

    /** Keystore can fail transiently (early boot, right after an OTA on some Samsungs); retry instead of crashing. */
    private val identity: DeviceIdentity by lazy {
        repeat(2) { attempt ->
            runCatching { return@lazy KeystoreIdentity.loadOrCreate(store.deviceName) }
                .onFailure { Log.w(TAG, "Keystore unavailable (attempt ${attempt + 1})", it); SystemClock.sleep(300) }
        }
        KeystoreIdentity.loadOrCreate(store.deviceName)
    }

    /** Short wake lock while data arrives, so a large clip isn't cut off by the CPU suspending. */
    private val receiveLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Syncliko:receive").apply { setReferenceCounted(false) }

    private val client: SyncClient by lazy {
        SyncClient(
            identity,
            hello,
            scope,
            discover = { listOfNotNull(nsd.latest) },
            clock = SystemClock::elapsedRealtime,
            onFrame = { runCatching { receiveLock.acquire(10_000) } },
        ).also { observe(it) }
    }

    private val _mac = MutableStateFlow(store.pairedMac)
    val mac: StateFlow<MacTarget?> = _mac.asStateFlow()

    private val _connection = MutableStateFlow<SyncClient.State>(SyncClient.State.Idle)
    val connection: StateFlow<SyncClient.State> = _connection.asStateFlow()

    private val power = context.getSystemService(PowerManager::class.java)

    /** Disconnected on purpose because the screen is off (see [onScreenOff]). */
    private val _dozing = MutableStateFlow(false)
    val dozing: StateFlow<Boolean> = _dozing.asStateFlow()
    private var doze: kotlinx.coroutines.Job? = null

    private val _hostedByAccessibility = MutableStateFlow(false)
    /** True while the accessibility service, not the foreground service, keeps sync running. */
    val hostedByAccessibility: StateFlow<Boolean> = _hostedByAccessibility.asStateFlow()

    private val _problem = MutableStateFlow(ConnectionProblem.NONE)
    val problem: StateFlow<ConnectionProblem> = _problem.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    /** A pairing link from outside the app (browser, camera) waiting for the user's OK. */
    private val _pendingInvite = MutableStateFlow<PairingInvite?>(null)
    val pendingInvite: StateFlow<PairingInvite?> = _pendingInvite.asStateFlow()

    private val _paused = MutableStateFlow(store.syncPaused)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _autoCapture = MutableStateFlow(store.autoCapture)
    val autoCapture: StateFlow<Boolean> = _autoCapture.asStateFlow()

    private val _dynamicColor = MutableStateFlow(store.dynamicColor)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    val capture = CaptureEngine(
        context,
        isEnabled = { _autoCapture.value && !_paused.value && _mac.value != null },
        snapshot = { clipboard.snapshot() },
        convert = { clipboard.toClip(it) },
        onCaptured = { clip -> send(clip, force = false) },
    )

    private val echo = EchoGuard()
    @Volatile private var lastLocal: Clip? = null
    /** A clip copied while offline (or not acknowledged); sent as soon as we reconnect (if still fresh). */
    @Volatile private var pending: Clip? = null

    private val historyLoaded = CompletableDeferred<Unit>()
    private val historyWrites = Channel<List<HistoryItem>>(Channel.CONFLATED)

    init {
        nsd.onFound = { client.reconnectNow() }
        // Screen on/off drives the connection (see onScreenOff). Registered here, not in SyncService,
        // because sync may run without that service.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff() else onUserPresent()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(_problem, _hostedByAccessibility) { problem, hosted -> problem.takeIf { hosted } ?: ConnectionProblem.NONE }
                .distinctUntilChanged()
                .collect(::showProblemAlert)
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (_mac.value == null || _dozing.value) return
                // A new default network: the old socket may be bound to a network that's gone.
                scope.launch { client.ensureAlive(staleMs = 0) }
            }

            override fun onLost(network: Network) {
                if (_mac.value != null && !_dozing.value) client.dropConnection("network lost")
            }
        })
        scope.launch(Dispatchers.IO) {
            _history.value = historyStore.load().map { (entry, clip) ->
                HistoryItem(clip, if (entry.received) HistoryItem.Direction.RECEIVED else HistoryItem.Direction.SENT, entry.at)
            }
            historyLoaded.complete(Unit)
            // Single writer: saves land in order and a stale list can never overwrite a newer one.
            for (items in historyWrites) {
                historyStore.save(items.map { HistoryStore.Entry(it.clip.header, it.direction == HistoryItem.Direction.RECEIVED, it.at) to it.clip })
            }
        }
    }

    val isConnected: Boolean get() = _connection.value is SyncClient.State.Connected

    /** Short, human-comparable form of this phone's certificate fingerprint. */
    val phoneFingerprint: String get() = identity.fingerprint.toHex().take(16).chunked(4).joinToString(" ")

    // region Lifecycle

    /**
     * Who keeps our process alive while the screen is on. The system binds an enabled accessibility
     * service with BIND_FOREGROUND_SERVICE_WHILE_AWAKE, which is exactly the lifetime the connection
     * needs now that it sleeps with the screen ([onScreenOff]). So with the accessibility service
     * running there's no foreground service and no notification. Without it (Pro-only or manual
     * mode, or One UI put the service to sleep) the foreground service takes over.
     */
    val accessibilityHosted: Boolean get() = CopyAccessibilityService.instance != null

    /** Keeps sync running if paired: directly under the accessibility service, else via [SyncService]. */
    fun ensureRunning() {
        if (_mac.value == null) return
        if (accessibilityHosted) {
            _hostedByAccessibility.value = true
            if (SyncService.running) context.stopService(SyncService.intent(context))
            startSync()
            return
        }
        _hostedByAccessibility.value = false
        // Android 12+ refuses FGS starts from the background unless the app is exempt from battery
        // optimization (Syncliko asks for that); otherwise the watchdog and the next foreground entry
        // point (app open, tile, share) retry.
        runCatching { ContextCompat.startForegroundService(context, SyncService.intent(context)) }
            .onFailure { Log.w(TAG, "can't start sync service now: ${it.message}") }
    }

    /** The accessibility service connected or went away: switch who hosts sync. */
    fun onAccessibilityChanged() {
        Log.i(TAG, "accessibility service ${if (accessibilityHosted) "connected: running without a foreground service" else "gone: foreground service takes over"}")
        ensureRunning()
    }

    /** [SyncService] stopped. Sync goes on if the accessibility service hosts it. */
    fun onServiceStopped() {
        if (!accessibilityHosted) stopSync()
    }

    /** Idempotent: safe to call from every service start. */
    fun startSync() {
        val target = _mac.value ?: return
        capture.start()
        // Started with the screen off (boot, watchdog, service restart): connect when the screen comes on.
        if (!power.isInteractive) {
            _dozing.value = true
            return
        }
        _dozing.value = false
        startDiscovery()
        client.start(target)
    }

    fun stopSync() {
        doze?.cancel()
        client.stop()
        nsd.stop()
        capture.stop()
    }

    fun reconnectNow() {
        if (!_dozing.value) client.reconnectNow()
    }

    /** Screen turned on / phone unlocked: connect (or verify the connection) before the user needs it. */
    fun onUserPresent() {
        if (_mac.value == null) return
        doze?.cancel()
        doze = null
        if (_dozing.value) {
            Log.i(TAG, "screen on: reconnecting")
            startSync()
        } else {
            scope.launch { client.ensureAlive() }
        }
    }

    /**
     * Screen off: nobody copies anything and Mac → phone copies are fetched on reconnect (the Mac
     * keeps its latest copy for [SynclikoProtocol.LATEST_MAX_AGE_MS]). So after a short grace period
     * we close the connection and stop discovery: no keepalives, no reconnect attempts, no multicast
     * while the phone sleeps. The grace period avoids reconnecting after a brief screen timeout.
     */
    fun onScreenOff() {
        if (_mac.value == null || _dozing.value) return
        doze?.cancel()
        doze = scope.launch {
            delay(SCREEN_OFF_GRACE_MS)
            if (power.isInteractive) return@launch
            Log.i(TAG, "screen off: closing the connection until the screen is back on")
            _dozing.value = true
            client.stop()
            nsd.stop()
        }
    }

    /** Bonjour only with the local-network permission; otherwise Android 17 shows a picker each time. */
    private fun startDiscovery() {
        if (LocalNetwork.isGranted(context)) nsd.start()
    }

    fun setPaused(paused: Boolean) {
        store.syncPaused = paused
        _paused.value = paused
    }

    fun setAutoCapture(enabled: Boolean) {
        store.autoCapture = enabled
        _autoCapture.value = enabled
    }

    fun setDynamicColor(enabled: Boolean) {
        store.dynamicColor = enabled
        _dynamicColor.value = enabled
    }

    // endregion

    // region Pairing

    /** A link from outside the app: never pair silently, ask the user first. */
    fun offerInvite(url: String) {
        PairingInvite.parse(url)?.let { _pendingInvite.value = it }
    }

    fun confirmInvite() {
        val invite = _pendingInvite.value ?: return
        _pendingInvite.value = null
        pair(invite)
    }

    fun dismissInvite() {
        _pendingInvite.value = null
    }

    /** Pairing the user started in the app (scanner, pasted link). */
    fun pair(url: String) {
        val invite = PairingInvite.parse(url)
        if (invite == null) {
            _pairing.value = PairingState.Failed(context.getString(R.string.pair_error_not_code))
            return
        }
        pair(invite)
    }

    private fun pair(invite: PairingInvite) {
        if (_pairing.value is PairingState.Connecting || _pairing.value is PairingState.AwaitingConfirmation) return
        _pairing.value = PairingState.Connecting
        scope.launch {
            stopSync()
            try {
                val code = PairingCode.make(invite.fingerprint, identity.fingerprint)
                val outcome = SyncClient.pair(identity, hello, invite, scope) {
                    _pairing.value = PairingState.AwaitingConfirmation(invite.deviceName, code)
                }
                when (outcome) {
                    is PairOutcome.Paired -> {
                        store.pairedMac = outcome.target
                        _mac.value = outcome.target
                        _problem.value = ConnectionProblem.NONE
                        _pairing.value = PairingState.Done(outcome.target.name)
                    }
                    is PairOutcome.Failed -> _pairing.value = PairingState.Failed(describePairingFailure(outcome.reason))
                }
            } catch (e: Exception) {
                _pairing.value = PairingState.Failed(e.message ?: "pairing failed")
            } finally {
                // Whatever happened, a paired phone must keep syncing.
                if (_mac.value != null) {
                    ensureRunning()
                    startSync()
                }
            }
        }
    }

    fun cancelPairing() {
        _pairing.value = PairingState.Idle
        if (_mac.value != null) startSync()
    }

    private fun describePairingFailure(reason: String): String {
        val r = reason.lowercase()
        val res = when {
            "rejected" in r -> R.string.pair_error_rejected
            "certificate" in r && "match" in r -> R.string.pair_error_certificate
            "token" in r || "pairing window" in r || "pairingclosed" in r -> R.string.pair_error_expired
            "reach" in r || "timeout" in r || "timed out" in r || "refused" in r || "unreachable" in r -> R.string.pair_error_unreachable
            else -> return reason
        }
        return context.getString(res)
    }

    /** The Google code scanner couldn't start (no Play services module, camera error, …). */
    fun scannerFailed(reason: String) {
        _pairing.value = PairingState.Failed(context.getString(R.string.pair_error_scanner, reason))
    }

    fun resetPairing() {
        _pairing.value = PairingState.Idle
    }

    fun unpair() {
        stopSync()
        context.stopService(SyncService.intent(context))
        store.pairedMac = null
        _mac.value = null
        _problem.value = ConnectionProblem.NONE
        _pairing.value = PairingState.Idle
    }

    // endregion

    // region Clipboard

    /**
     * Sends the current clipboard. Grabs the ClipData on the calling (focused, main) thread and does the
     * conversion — which may read a large image — on a background thread.
     */
    fun sendCurrentClipboard(force: Boolean = false, onResult: (SendResult) -> Unit = {}) {
        val data = clipboard.snapshot() ?: return onResult(SendResult.NOTHING)
        sendSnapshot(data, force, onResult)
    }

    fun sendSnapshot(data: ClipData, force: Boolean, onResult: (SendResult) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val clip = runCatching { clipboard.toClip(data) }.getOrNull()
            val result = clip?.let { send(it, force) } ?: SendResult.NOTHING
            launch(Dispatchers.Main) { onResult(result) }
        }
    }

    fun send(clip: Clip, force: Boolean = true): SendResult {
        if (_paused.value) return SendResult.PAUSED
        if (clip.content.size > SynclikoProtocol.MAX_CLIP_SIZE) return SendResult.TOO_LARGE
        val hash = clip.header.sha256
        if (!force && echo.shouldSuppress(hash)) return SendResult.DUPLICATE
        echo.note(hash)
        lastLocal = clip
        capture.noteExternalClip(hash)
        return if (client.send(clip)) {
            record(clip, HistoryItem.Direction.SENT)
            SendResult.SENT
        } else {
            pending = clip
            ensureRunning()
            if (_dozing.value) onUserPresent() else client.reconnectNow()
            SendResult.QUEUED
        }
    }

    private fun observe(client: SyncClient) {
        scope.launch {
            client.state.collect { state ->
                val wasConnected = _connection.value is SyncClient.State.Connected
                _connection.value = state
                if (wasConnected != (state is SyncClient.State.Connected)) {
                    runCatching { TileService.requestListeningState(context, ComponentName(context, ClipboardTileService::class.java)) }
                }
                when (state) {
                    is SyncClient.State.Connected -> {
                        _problem.value = ConnectionProblem.NONE
                        nsd.stop()
                        flushPending()
                    }
                    is SyncClient.State.Waiting -> {
                        _problem.value = classify(state.error)
                        startDiscovery()
                    }
                    else -> if (_mac.value != null && state !is SyncClient.State.Idle) startDiscovery()
                }
            }
        }
        scope.launch { client.clips.collect(::received) }
        scope.launch {
            client.undelivered.collect { clip ->
                if (pending == null) pending = clip
            }
        }
        scope.launch {
            client.endpoints.collect { endpoints ->
                val mac = _mac.value ?: return@collect
                if (endpoints.isEmpty()) return@collect
                // Keep what the Mac reports plus anything we learned elsewhere.
                val merged = (endpoints + mac.endpoints).distinctBy { it.host to it.port }.take(8)
                val updated = mac.copy(endpoints = merged)
                if (updated != mac) {
                    store.pairedMac = updated
                    _mac.value = updated
                }
            }
        }
        scope.launch {
            client.latestRequests.collect {
                val clip = lastLocal ?: return@collect
                if (System.currentTimeMillis() - clip.header.createdAt < SynclikoProtocol.LATEST_MAX_AGE_MS) {
                    client.send(clip)
                }
            }
        }
    }

    private fun classify(error: String?): ConnectionProblem {
        val e = error?.lowercase() ?: return ConnectionProblem.NONE
        return when {
            "identity changed" in e -> ConnectionProblem.MAC_IDENTITY_CHANGED
            // TLS 1.3: the Mac verifies our certificate after the handshake and answers with an alert.
            "certificate" in e || "bad_certificate" in e || "unauthorized" in e || "alert" in e -> ConnectionProblem.MAC_FORGOT_PHONE
            else -> ConnectionProblem.NONE
        }
    }

    private fun flushPending() {
        val clip = pending ?: return
        pending = null
        if (System.currentTimeMillis() - clip.header.createdAt < SynclikoProtocol.LATEST_MAX_AGE_MS && client.send(clip)) {
            record(clip, HistoryItem.Direction.SENT)
        }
    }

    private fun received(clip: Clip) {
        if (_paused.value) return
        // Our own clip echoed back (or mirrored by another sync tool): it's already on the clipboard.
        if (echo.shouldSuppress(clip.header.sha256)) return
        echo.note(clip.header.sha256)
        lastLocal = clip
        capture.noteOwnWrite(clip.header.sha256)
        runCatching { clipboard.write(clip) }.onFailure { Log.w(TAG, "clipboard write failed", it) }
        record(clip, HistoryItem.Direction.RECEIVED)
        runCatching { receiveLock.release() }
    }

    private fun record(clip: Clip, direction: HistoryItem.Direction) {
        scope.launch {
            historyLoaded.await()
            val updated = updateHistory { list ->
                (listOf(HistoryItem(clip, direction)) + list.filter { it.clip.header.sha256 != clip.header.sha256 }).take(HISTORY_LIMIT)
            }
            historyWrites.trySend(updated)
        }
    }

    private fun updateHistory(transform: (List<HistoryItem>) -> List<HistoryItem>): List<HistoryItem> {
        while (true) {
            val prev = _history.value
            val next = transform(prev)
            if (_history.compareAndSet(prev, next)) return next
        }
    }

    fun clearHistory() {
        historyWrites.trySend(updateHistory { emptyList() })
    }

    fun deleteHistoryItem(item: HistoryItem) {
        historyWrites.trySend(updateHistory { list -> list.filter { it.key != item.key } })
    }

    /** Copies a history entry back to the clipboard (and to the Mac). */
    fun restore(item: HistoryItem, onResult: (SendResult) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val clip = Clip.make(item.clip.kind, item.clip.header.mime, item.clip.content, store.deviceId)
            runCatching { clipboard.write(clip) }
            echo.reset()
            val result = send(clip, force = true)
            launch(Dispatchers.Main) { onResult(result) }
        }
    }

    // endregion

    /**
     * Without the foreground service there's no status notification to carry problems the user must
     * fix (the Mac forgot this phone, its identity changed), so post a regular one for those.
     */
    private fun showProblemAlert(problem: ConnectionProblem) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (problem == ConnectionProblem.NONE) {
            nm.cancel(ALERT_ID)
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, context.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.alert_title, _mac.value?.name ?: "Mac"))
            .setContentText(context.getString(R.string.notif_problem))
            .setColor(0xFF4A56D1.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ALERT_ID, notification) }
    }

    private companion object {
        const val TAG = "Syncliko"
        const val ALERT_CHANNEL = "alerts"
        const val ALERT_ID = 2
        const val SCREEN_OFF_GRACE_MS = 30_000L
        const val HISTORY_LIMIT = 30
    }
}

/**
 * Suppresses echoes (content we just sent or received coming back) for a short window only, so a
 * deliberate re-copy of the same text later still syncs.
 */
private class EchoGuard {
    @Volatile private var hash: String? = null
    @Volatile private var at = 0L

    fun shouldSuppress(h: String) = h == hash && SystemClock.elapsedRealtime() - at < 15_000
    fun note(h: String) {
        hash = h
        at = SystemClock.elapsedRealtime()
    }
    fun reset() {
        hash = null
    }
}
