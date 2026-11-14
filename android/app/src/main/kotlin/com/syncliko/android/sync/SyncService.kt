package com.syncliko.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syncliko.android.R
import com.syncliko.android.SynclikoApp
import com.syncliko.android.capture.CaptureEngine
import com.syncliko.android.capture.ClipboardSendActivity
import com.syncliko.android.capture.ClipboardTileService
import com.syncliko.android.ui.MainActivity
import com.syncliko.protocol.Endpoint
import com.syncliko.transport.SyncClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps the connection to the Mac alive, so Mac → phone copies land
 * instantly even when the app is in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncService : LifecycleService() {
    private val controller get() = (application as SynclikoApp).controller

    /** The user swiped the notification away (allowed for FGS on Android 14+): don't keep re-posting it. */
    private var dismissed = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startInForeground(buildNotification(getString(R.string.notif_connecting, controller.mac.value?.name ?: "Mac"), null, connected = false, paused = false))) {
            stopSelf()
            return
        }
        running = true
        lifecycleScope.launch {
            combine(controller.connection, controller.mac, controller.paused, controller.problem, controller.capture.status) { state, mac, paused, problem, capture ->
                val connected = state is SyncClient.State.Connected
                NotificationModel(
                    connected = connected,
                    paused = paused,
                    important = problem != ConnectionProblem.NONE || (capture.proPending && !capture.accessibilityOn),
                    title = when {
                        paused -> getString(R.string.notif_paused)
                        state is SyncClient.State.Connected -> getString(
                            if (state.endpoint.kind == Endpoint.Kind.TAILSCALE) R.string.notif_connected_tailscale else R.string.notif_connected,
                            state.peer.deviceName,
                        )
                        state is SyncClient.State.Waiting -> getString(R.string.notif_waiting, mac?.name ?: "Mac")
                        else -> getString(R.string.notif_connecting, mac?.name ?: "Mac")
                    },
                    text = when {
                        problem != ConnectionProblem.NONE -> getString(R.string.notif_problem)
                        paused -> null
                        capture.proPending && !capture.accessibilityOn -> getString(R.string.notif_pro_pending)
                        controller.autoCapture.value && capture.method != CaptureEngine.Method.MANUAL -> getString(R.string.notif_auto_on)
                        else -> getString(R.string.notif_auto_off)
                    },
                )
            }
                .distinctUntilChanged()
                // While the screen is off the connection sleeps on purpose; nobody sees the notification.
                .combine(controller.dozing) { model, dozing -> model.takeUnless { dozing } }
                // Reconnects take well under a second: don't flash "Connecting…" for them. Only a state
                // that lasts is worth a notification update (each one wakes SystemUI).
                .mapLatest { model ->
                    if (model != null && !model.connected && !model.paused && !model.important) delay(STATE_SETTLE_MS)
                    model
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { model ->
                    if (dismissed && !model.important) return@collect
                    dismissed = false
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(model.title, model.text, model.connected, model.paused))
                    runCatching { TileService.requestListeningState(this@SyncService, ComponentName(this@SyncService, ClipboardTileService::class.java)) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> controller.setPaused(true)
            ACTION_RESUME -> controller.setPaused(false)
            ACTION_DISMISSED -> {
                dismissed = true
                return START_STICKY
            }
        }
        if (controller.mac.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The accessibility service keeps the process alive now: no need for this service or its notification.
        if (controller.accessibilityHosted) {
            stopSelf()
            controller.ensureRunning()
            return START_NOT_STICKY
        }
        controller.startSync()
        return START_STICKY
    }

    /** Android 15+: called if the system decides the foreground service ran too long. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("Syncliko", "foreground service timeout ($fgsType)")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        controller.onServiceStopped()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException / missing type prerequisites: don't crash-loop.
        Log.w("Syncliko", "can't run in foreground: ${e.message}")
        false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationModel(val connected: Boolean, val paused: Boolean, val important: Boolean, val title: String, val text: String?)

    private fun buildNotification(title: String, text: String?, connected: Boolean, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val send = PendingIntent.getActivity(this, 1, ClipboardSendActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(
            this,
            2,
            intent(this).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF4A56D1.toInt())
            .setContentIntent(open)
            .setDeleteIntent(PendingIntent.getService(this, 3, intent(this).setAction(ACTION_DISMISSED), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (connected && !paused) addAction(R.drawable.ic_tile, getString(R.string.action_send_clipboard), send)
                addAction(R.drawable.ic_tile, getString(if (paused) R.string.action_resume else R.string.action_pause), toggle)
            }
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.syncliko.android.PAUSE"
        private const val ACTION_RESUME = "com.syncliko.android.RESUME"
        private const val ACTION_DISMISSED = "com.syncliko.android.NOTIFICATION_DISMISSED"
        private const val STATE_SETTLE_MS = 2_500L
        const val CHANNEL_ID = "sync"
        fun intent(context: Context) = Intent(context, SyncService::class.java)

        /** Whether the service is alive in this process (stopService on a stopped service is harmless, but skip it). */
        @Volatile var running = false
            private set
    }
}

/** Restarts sync after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as SynclikoApp).controller.ensureRunning()
        Watchdog.schedule(context)
    }
}

/**
 * Every 15 minutes, brings the service back if the system or an OEM battery manager killed it.
 * Apps exempt from battery optimization may start a foreground service from the background.
 */
class Watchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as SynclikoApp).controller.ensureRunning()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "syncliko-watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
