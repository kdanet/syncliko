package com.syncliko.android.capture

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CopySignalsTest {
    @Test
    fun copyClicks() {
        assertTrue(CopySignals.isCopyClick(listOf("Copy"), null))
        assertTrue(CopySignals.isCopyClick(listOf("Копировать"), null))
        assertTrue(CopySignals.isCopyClick(emptyList(), "Copy link"))
        assertTrue(CopySignals.isCopyClick(listOf("  Скопировать ссылку "), null))
        assertFalse(CopySignals.isCopyClick(listOf("Copyright 2026"), null))
        assertFalse(CopySignals.isCopyClick(listOf("Paste"), null))
        assertFalse(CopySignals.isCopyClick(listOf("copy of the contract draft that is long"), null))
    }

    @Test
    fun selectionMenus() {
        assertTrue(CopySignals.isSelectionMenu(listOf("Copy", "Share", "Select all")))
        assertTrue(CopySignals.isSelectionMenu(listOf("Вырезать", "Копировать", "Вставить", "Выбрать все")))
        assertFalse(CopySignals.isSelectionMenu(listOf("Selectable: long-press me and choose Copy")))
        assertFalse(CopySignals.isSelectionMenu(listOf("Share", "Select all")))
    }

    @Test
    fun confirmations() {
        assertTrue(CopySignals.isCopiedConfirmation(listOf("Copied to clipboard")))
        assertTrue(CopySignals.isCopiedConfirmation(listOf("Скопировано в буфер обмена")))
        assertTrue(CopySignals.isCopiedConfirmation(listOf("Ссылка скопирована")))
        assertFalse(CopySignals.isCopiedConfirmation(listOf("Message sent")))
    }

    @Test
    fun clipboardOverlay() {
        assertTrue(CopySignals.isClipboardOverlay("com.android.systemui", "com.android.systemui.clipboardoverlay.ClipboardOverlayWindow"))
        assertFalse(CopySignals.isClipboardOverlay("com.android.systemui", "android.widget.FrameLayout"))
        assertFalse(CopySignals.isClipboardOverlay("org.telegram.messenger", "ClipboardHelper"))
    }

    @Test
    fun logcatDenials() {
        val pkg = "com.syncliko.android"
        val aosp = "1731552000.123  1000  1234 E ClipboardService: Denying clipboard access to com.syncliko.android, application is not in focus nor is it a system service for user 0"
        assertTrue(CopySignals.isClipboardDenial(aosp, pkg))
        assertEquals(1731552000123L, CopySignals.epochMillis(aosp))
        assertFalse(CopySignals.isClipboardDenial(aosp.replace(pkg, "com.other.app"), pkg))
        assertFalse(CopySignals.isClipboardDenial("1731552000.1 1000 1 I ActivityManager: Start proc com.syncliko.android", pkg))
    }
}
