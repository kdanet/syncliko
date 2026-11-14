package com.syncliko.android.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Android 10+ lets an app read the clipboard only while one of its windows has input focus.
 *
 * Strategy, cheapest first:
 * 1. An invisible 1×1 focusable overlay window (TYPE_ACCESSIBILITY_OVERLAY from the Accessibility
 *    service, or TYPE_APPLICATION_OVERLAY with "Display over other apps"). No task switch at all.
 * 2. If focus doesn't arrive quickly (some builds don't hand focus to overlays), a transparent
 *    [FocusActivity] that reads in onWindowFocusChanged and finishes immediately. Starting it from
 *    the background is allowed for apps with an enabled Accessibility service or the overlay permission.
 */
object FocusReader {
    private const val TAG = "SynclikoFocus"
    private const val OVERLAY_WAIT_MS = 700L
    private const val ACTIVITY_WAIT_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    fun <T> read(context: Context, overlayType: Int?, onFocused: () -> T, onResult: (T?) -> Unit) {
        main.post {
            if (busy) {
                Log.d(TAG, "busy, skipping")
                onResult(null)
                return@post
            }
            busy = true
            val done: (T?) -> Unit = { value ->
                busy = false
                onResult(value)
            }
            if (overlayType != null) {
                viaOverlay(context, overlayType, onFocused) { focused, value ->
                    if (focused) done(value) else viaActivity(context, onFocused, done)
                }
            } else {
                viaActivity(context, onFocused, done)
            }
        }
    }

    private fun <T> viaOverlay(context: Context, windowType: Int, onFocused: () -> T, onResult: (focused: Boolean, T?) -> Unit) {
        val wm = context.getSystemService(WindowManager::class.java)
        var finished = false
        lateinit var view: View

        fun finish(focused: Boolean, value: T?) {
            if (finished) return
            finished = true
            // Never remove the window synchronously from its own focus callback: ViewRootImpl keeps
            // dispatching the focus change after we return and crashes on a detached view.
            main.post { runCatching { wm.removeView(view) } }
            onResult(focused, value)
        }

        view = object : View(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) finish(true, runCatching(onFocused).onFailure { Log.w(TAG, "read failed", it) }.getOrNull())
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Don't take over the keyboard of the app the user is typing in.
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Not 0: a fully transparent window is treated as hidden and never gets focus.
            alpha = 0.01f
            title = "Syncliko clipboard reader"
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "can't add focus window: ${e.message}")
            onResult(false, null)
            return
        }
        main.postDelayed({
            if (!finished) Log.d(TAG, "overlay didn't get focus, falling back to activity")
            finish(false, null)
        }, OVERLAY_WAIT_MS)
    }

    private fun <T> viaActivity(context: Context, onFocused: () -> T, onResult: (T?) -> Unit) {
        var finished = false
        fun finish(value: T?) {
            if (finished) return
            finished = true
            FocusActivity.pending = null
            onResult(value)
        }
        FocusActivity.pending = { finish(runCatching(onFocused).onFailure { Log.w(TAG, "read failed", it) }.getOrNull()) }
        try {
            context.startActivity(
                Intent(context, FocusActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "can't start focus activity: ${e.message}")
            finish(null)
            return
        }
        main.postDelayed({
            if (!finished) Log.d(TAG, "focus activity timed out")
            finish(null)
        }, ACTIVITY_WAIT_MS)
    }
}

/** Transparent, animation-less activity that exists for one frame to gain input focus. */
class FocusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        if (pending == null) finishQuietly()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        pending?.invoke()
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        @Volatile var pending: (() -> Unit)? = null
    }
}
