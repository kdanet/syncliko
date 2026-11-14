package com.syncliko.android.capture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.syncliko.protocol.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Automatic phone → Mac capture.
 *
 * Something tells us "the user probably just copied" — the Accessibility service (Standard mode)
 * or a ClipboardService denial line in logcat (Pro mode, needs READ_LOGS granted over ADB). We
 * then briefly take input focus with [FocusReader], read the clipboard and hand it to [onCaptured].
 */
class CaptureEngine(
    private val context: Context,
    private val isEnabled: () -> Boolean,
    /** Runs while focused (main thread): must be quick — just grab the ClipData. */
    private val snapshot: () -> ClipData?,
    /** Runs on a background thread: turns the snapshot into a clip (may read image streams). */
    private val convert: (ClipData) -> Clip?,
    private val onCaptured: (Clip) -> Unit,
) {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "syncliko-capture") }
    enum class Method { PRO, STANDARD, MANUAL }

    data class Status(
        /** The Accessibility service is bound and running. */
        val accessibilityOn: Boolean,
        /** Enabled in settings but not running (an OEM killed it): the user must toggle it again. */
        val accessibilityStalled: Boolean = false,
        val logsGranted: Boolean,
        val overlayGranted: Boolean,
        /** Log lines actually arrive (Android 13+ asks the user to allow log access per app start). */
        val logsConfirmed: Boolean = false,
    ) {
        /** Pro is set up but log access hasn't been confirmed yet (prompt pending, missed or denied). */
        val proPending: Boolean get() = method == Method.PRO && !logsConfirmed

        /** The best method that can actually run right now. */
        val method: Method
            get() = when {
                logsGranted && (overlayGranted || accessibilityOn) -> Method.PRO
                accessibilityOn -> Method.STANDARD
                else -> Method.MANUAL
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var running = false
    private var logcat: LogcatWatcher? = null
    private var pending: Runnable? = null
    private var reading = false
    private var again = false
    private var lastTriggerAt = 0L
    private var lastCapturedHash: String? = null
    @Volatile private var quietUntil = 0L
    private var lastReadEndedAt = 0L
    private var lastCaptureAt = 0L
    private val recentReads = ArrayDeque<Long>()

    /**
     * Registered only to make ClipboardService log a denial for us on every copy (Pro mode).
     */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Intentionally empty: the registration alone makes ClipboardService log a denial on every
        // copy (Pro mode signal). Reads triggered from here would only race with real signals.
    }

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start() }
            return
        }
        if (running) return
        running = true
        runCatching { clipboard.addPrimaryClipChangedListener(clipListener) }
        refresh()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        running = false
        runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
        logcat?.stop()
        logcat = null
    }

    /** Re-check permissions (after returning from Settings, service (dis)connect, …). */
    fun refresh() {
        val status = currentStatus()
        _status.value = status
        if (running && status.logsGranted) {
            // Only start while our UI is visible: Android asks the user for log access only then, and a
            // request from the background is declined (and that decline can stick until reboot).
            if (logcat == null && isForeground()) {
                logcat = LogcatWatcher(
                    context.packageName,
                    startedInForeground = isForeground(),
                    onConfirmed = { main.post { _status.value = currentStatus() } },
                ) { trigger("logcat", retry = false, logSignal = true) }
                    .also { it.start() }
            }
        } else {
            logcat?.stop()
            logcat = null
        }
    }

    /**
     * A copy probably happened. Debounced: bursts of signals (click + toast + log line) collapse into
     * one read. [retry] re-reads once more later for apps that write the clipboard asynchronously.
     */
    fun trigger(reason: String, retry: Boolean = true, settleMs: Long = 0, windowSignal: Boolean = false, logSignal: Boolean = false) {
        if (!running || !isEnabled()) {
            Log.d(TAG, "[$reason] ignored (running=$running enabled=${isEnabled()})")
            return
        }
        main.post {
            val now = SystemClock.uptimeMillis()
            if (now < quietUntil) {
                Log.d(TAG, "[$reason] ignored: quiet period")
                return@post
            }
            // Our focus window makes other windows re-announce themselves (Samsung does this for its
            // clipboard and edge panels). Window signals right around our own read are that echo.
            // One copy often logs two denials (the app and Samsung's clipboard both touch it); the
            // second one right after a successful capture is the same copy.
            if (logSignal && (reading || now - lastCaptureAt < LOG_REPEAT_MS)) {
                Log.d(TAG, "[$reason] ignored: repeat of the copy just captured")
                return@post
            }
            if (windowSignal && (reading || now - lastReadEndedAt < FOCUS_ECHO_MS)) {
                Log.d(TAG, "[$reason] ignored: echo of our focus window")
                return@post
            }
            // Circuit breaker: whatever the cause, never read in a tight loop.
            while (recentReads.isNotEmpty() && now - recentReads.first() > LOOP_WINDOW_MS) recentReads.removeFirst()
            if (recentReads.size >= LOOP_MAX_READS) {
                Log.w(TAG, "[$reason] too many reads, pausing capture for ${LOOP_PAUSE_MS} ms")
                recentReads.clear()
                quietUntil = now + LOOP_PAUSE_MS
                return@post
            }
            if (pending != null) {
                Log.d(TAG, "[$reason] merged into pending read")
                return@post
            }
            if (reading) {
                // A read is in flight; run one more right after it so this copy isn't lost.
                again = true
                return@post
            }
            val delay = maxOf(settleMs, if (now - lastTriggerAt < 120) 120L else 60L)
            lastTriggerAt = now
            val task = Runnable {
                pending = null
                reading = true
                recentReads.addLast(SystemClock.uptimeMillis())
                captureOnce(reason) { changed ->
                    reading = false
                    lastReadEndedAt = SystemClock.uptimeMillis()
                    when {
                        again -> {
                            again = false
                            trigger("$reason-again", retry = false)
                        }
                        !changed && retry -> main.postDelayed({ trigger("$reason-retry", retry = false) }, 650)
                    }
                }
            }
            pending = task
            main.postDelayed(task, delay)
        }
    }

    private fun captureOnce(reason: String, done: (changed: Boolean) -> Unit) {
        // Screen off or locked: nobody is copying, and a focus window would wait behind the keyguard.
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            done(true)
            return
        }
        val host = readHost()
        if (host == null) {
            Log.d(TAG, "[$reason] no way to read the clipboard in the background")
            done(false)
            return
        }
        FocusReader.read(host.first, host.second, onFocused = snapshot) { data ->
            if (data == null) {
                Log.d(TAG, "[$reason] read result: no clipboard data (focus or access failed)")
                done(false)
                return@read
            }
            worker.execute {
                val clip = runCatching { convert(data) }.getOrNull()
                main.post {
                    Log.d(TAG, "[$reason] read result: ${clip?.kind ?: "nothing"}")
                    val changed = clip != null && clip.header.sha256 != lastCapturedHash
                    if (clip != null && changed) {
                        lastCapturedHash = clip.header.sha256
                        lastCaptureAt = SystemClock.uptimeMillis()
                        Log.d(TAG, "[$reason] captured ${clip.kind} ${clip.content.size} B")
                        worker.execute { onCaptured(clip) }
                    }
                    done(changed)
                }
            }
        }
    }

    /**
     * Called when our UI comes to the foreground. Android 13+ shows the "allow access to device logs"
     * prompt only to a foreground app; a watcher started in the background (after boot) gets nothing.
     * Restart it while visible so the prompt appears, unless it already proved it can read logs.
     */
    fun onAppForeground() {
        val watcher = logcat
        if (watcher != null && !watcher.confirmed && !watcher.startedInForeground) {
            watcher.stop()
            logcat = null
        }
        refresh()
    }

    /** Remember content that arrived from the Mac so a later read of it isn't sent back. */
    fun noteExternalClip(hash: String) {
        lastCapturedHash = hash
    }

    /**
     * We are about to write a clip from the Mac. That write produces the same signals as a user copy
     * (Samsung's "Copied" toast, a ClipboardService denial line), so ignore signals for a moment instead
     * of flashing a focus window just to find our own clip.
     */
    fun noteOwnWrite(hash: String) {
        lastCapturedHash = hash
        quietUntil = SystemClock.uptimeMillis() + OWN_WRITE_QUIET_MS
    }

    /** Context to read from and the overlay window type it may use (null: activity only). */
    private fun readHost(): Pair<Context, Int?>? {
        CopyAccessibilityService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (Settings.canDrawOverlays(context)) return context to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return null
    }

    private fun isForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun currentStatus() = Status(
        accessibilityOn = CopyAccessibilityService.instance != null,
        accessibilityStalled = CopyAccessibilityService.instance == null && isAccessibilityEnabledInSettings(),
        logsGranted = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED,
        overlayGranted = Settings.canDrawOverlays(context),
        logsConfirmed = logcat?.confirmed == true,
    )

    /** Restart the log watcher while our UI is visible so Android shows the log-access prompt again. */
    fun requestLogAccess() {
        logcat?.stop()
        logcat = null
        refresh()
    }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }

    private companion object {
        const val TAG = "SynclikoCapture"
        const val OWN_WRITE_QUIET_MS = 1_200L
        const val FOCUS_ECHO_MS = 600L
        const val LOG_REPEAT_MS = 500L
        const val LOOP_WINDOW_MS = 3_000L
        const val LOOP_MAX_READS = 6
        const val LOOP_PAUSE_MS = 5_000L
    }
}

/**
 * Follows logcat for ClipboardService denials about our package (Pro mode). Needs READ_LOGS, which a
 * user grants once with `adb shell pm grant <pkg> android.permission.READ_LOGS`.
 */
class LogcatWatcher(
    private val packageName: String,
    /** Android shows the log-access prompt only to a foreground app; a background start is silently denied. */
    val startedInForeground: Boolean,
    private val onConfirmed: () -> Unit,
    private val onDenial: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var process: Process? = null

    /** True once a line from ClipboardService arrived, i.e. log access was actually approved. */
    @Volatile var confirmed = false
        private set

    fun start() {
        if (running) return
        running = true
        thread(name = "syncliko-logcat", isDaemon = true) {
            var retryMs = 3_000L
            while (running) {
                val startedAt = System.currentTimeMillis() - 500
                try {
                    val p = ProcessBuilder("logcat", "-b", "system,main", "-v", "epoch", "-T", "1", "-e", packageName)
                        .redirectErrorStream(true)
                        .start()
                    process = p
                    Log.i(TAG, "logcat started")
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!running) break
                            val denial = CopySignals.isClipboardDenial(line, packageName)
                            val fresh = (CopySignals.epochMillis(line) ?: Long.MAX_VALUE) >= startedAt
                            if (denial) {
                                if (!confirmed) {
                                    confirmed = true
                                    onConfirmed()
                                }
                                Log.i(TAG, "denial seen (fresh=$fresh)")
                            }
                            if (denial && fresh) onDenial()
                        }
                    }
                    Log.i(TAG, "logcat exited: ${runCatching { p.exitValue() }.getOrNull()}")
                } catch (e: Exception) {
                    Log.w(TAG, "logcat stopped: ${e.message}")
                }
                // Access denied or logcat died: back off (3 s … 5 min) instead of respawning in a tight loop.
                if (running) {
                    Thread.sleep(retryMs)
                    retryMs = if (confirmed) 3_000 else (retryMs * 2).coerceAtMost(300_000)
                }
            }
        }
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
    }

    private companion object {
        const val TAG = "SynclikoLogcat"
    }
}
