package com.syncliko.android.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.syncliko.android.SynclikoApp

/**
 * Standard capture mode. Watches only for copy signals — a tap on a "Copy" button or menu item, a
 * "Copied" toast, the system clipboard preview — and never reads screen content
 * (`canRetrieveWindowContent=false`). On a signal the engine reads the clipboard through a focus
 * window owned by this service (TYPE_ACCESSIBILITY_OVERLAY needs no extra permission).
 */
class CopyAccessibilityService : AccessibilityService() {
    private val controller get() = (application as SynclikoApp).controller
    private val engine get() = controller.capture
    private var menuArmedUntil = 0L
    private var menuPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine.refresh()
        controller.onAccessibilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        // Diagnostics: `adb shell setprop log.tag.SynclikoA11y DEBUG` (never logs event text otherwise).
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=${event.packageName} cls=${event.className} text=${event.text} desc=${event.contentDescription}")
        }
        val now = SystemClock.uptimeMillis()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // A selection toolbar / context menu offering "Copy" just appeared: arm.
        if (isWindowChange && CopySignals.isSelectionMenu(event.text)) {
            menuArmedUntil = now + MENU_ARM_MS
            menuPackage = event.packageName?.toString()
            return
        }

        val signal: String? = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                "click".takeIf { CopySignals.isCopyClick(event.text, event.contentDescription) }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                "toast".takeIf { CopySignals.isCopiedConfirmation(event.text) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> when {
                CopySignals.isClipboardOverlay(event.packageName, event.className) -> "overlay"
                CopySignals.isCopiedConfirmation(event.text) -> "copied-window"
                else -> null
            }
            else -> null
        }
        if (signal != null) {
            menuArmedUntil = 0
            // Which rule fired and where, never the text: enough to diagnose a misfiring heuristic.
            engine.trigger("a11y:$signal ${event.packageName}/${event.className}", windowSignal = isWindowChange)
            return
        }

        // The armed menu closed (focus went back to another window): the user may have tapped Copy.
        // If they didn't, the clipboard is unchanged and nothing is sent.
        if (isWindowChange && now < menuArmedUntil && event.packageName?.toString() != menuPackage) {
            menuArmedUntil = 0
            // Let focus settle back on the app before taking it for the read.
            engine.trigger("a11y:menu-closed ${event.packageName}", settleMs = 300, windowSignal = true)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    /** Hand sync back to the foreground service (it brings its notification back). */
    private fun disconnected() {
        if (instance !== this) return
        instance = null
        engine.refresh()
        controller.onAccessibilityChanged()
    }

    companion object {
        private const val TAG = "SynclikoA11y"
        private const val MENU_ARM_MS = 15_000L
        @Volatile var instance: CopyAccessibilityService? = null
            private set
    }
}
