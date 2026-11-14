package com.syncliko.android.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.HapticFeedbackConstants
import com.syncliko.android.R
import com.syncliko.android.SynclikoApp
import com.syncliko.android.sync.SendResult
import com.syncliko.android.ui.toast
import com.syncliko.protocol.Clip
import kotlin.concurrent.thread

/**
 * Invisible activity behind "Send to Mac" in the text-selection menu (`PROCESS_TEXT`) and the share
 * sheet (`SEND`, text or image). Sends and finishes immediately.
 */
class SendActivity : Activity() {
    private val controller get() = (application as SynclikoApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTransition(open = true)
        val intent = intent ?: return finish()
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                deliver(text?.let { controller.send(Clip.text(it, controller.store.deviceId)) } ?: SendResult.NOTHING)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                when {
                    uri != null && isForeignContentUri(uri) -> thread(name = "syncliko-share") {
                        // Reading a shared image can take a while: never on the main thread.
                        val result = controller.clipboard.imageClip(uri)?.let { controller.send(it) } ?: SendResult.NOTHING
                        runOnUiThread { deliver(result) }
                    }
                    !text.isNullOrEmpty() -> deliver(controller.send(Clip.text(text, controller.store.deviceId)))
                    else -> deliver(SendResult.NOTHING)
                }
            }
            else -> finish()
        }
    }

    /** Only content:// URIs from other apps: never files, never our own provider (confused deputy). */
    private fun isForeignContentUri(uri: Uri) =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority != "$packageName.clips"

    private fun deliver(result: SendResult) {
        if (result == SendResult.SENT) window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast(applicationContext, result, controller.mac.value?.name ?: "Mac")
        finish()
        noTransition(open = false)
    }
}

/**
 * Not exported: only our tile and notification can start it. Android lets apps read the clipboard only
 * while focused, so it reads in [onWindowFocusChanged] and finishes right away.
 */
class ClipboardSendActivity : Activity() {
    private val controller get() = (application as SynclikoApp).controller
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTransition(open = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        controller.sendCurrentClipboard(force = true) { result ->
            if (result == SendResult.SENT) window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            toast(applicationContext, result, controller.mac.value?.name ?: "Mac")
            finish()
            noTransition(open = false)
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, ClipboardSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

private fun Activity.noTransition(open: Boolean) {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(if (open) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** Quick Settings tile: one tap sends the phone's clipboard to the Mac. */
class ClipboardTileService : TileService() {
    private val controller get() = (application as SynclikoApp).controller

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val mac = controller.mac.value
            state = when {
                mac == null -> Tile.STATE_UNAVAILABLE
                controller.isConnected -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            subtitle = when {
                mac == null -> getString(R.string.tile_not_paired)
                controller.isConnected -> mac.name
                else -> getString(R.string.tile_offline)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = ClipboardSendActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
