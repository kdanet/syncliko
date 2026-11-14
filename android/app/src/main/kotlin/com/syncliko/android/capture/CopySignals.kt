package com.syncliko.android.capture

import java.util.Locale

/**
 * Pure heuristics that decide whether an observed UI event or log line means "the user just copied
 * something". Kept free of Android types so they are unit-tested on the JVM.
 */
object CopySignals {
    /** Labels of copy buttons and menu items (text-selection toolbar, share sheets, app buttons). */
    private val copyActions = listOf(
        "copy", "copy link", "copy text", "copy url", "copy address", "copy to clipboard", "copy code",
        "копировать", "скопировать", "копировать ссылку", "скопировать ссылку", "копировать текст",
        "скопировать текст", "копировать адрес", "скопировать адрес", "копировать код", "скопировать код",
        "kopieren", "link kopieren", "copier", "copier le lien", "copiar", "copiar enlace", "copiar link",
        "copia", "copia link", "kopiuj", "kopyala", "kopiëren", "kopioi", "kopiera", "kopier",
        "复制", "複製", "复制链接", "コピー", "リンクをコピー", "복사", "링크 복사",
    )

    /** Confirmation messages ("Copied to clipboard", Samsung's "Скопировано в буфер обмена", …). */
    private val copiedConfirmations = Regex(
        "copied|скопирован|скопировано|kopiert|copié|copiado|copiato|skopiowan|kopyaland|gekopieerd|" +
            "kopierad|kopioitu|已复制|已複製|コピーしました|복사되었|복사됨",
        RegexOption.IGNORE_CASE,
    )

    private val whitespace = Regex("\\s+")

    private fun normalize(s: CharSequence?): String =
        s?.toString()?.trim()?.lowercase(Locale.ROOT)?.replace(whitespace, " ")?.trimEnd('.', '…', '!') ?: ""

    /** A click on a view whose label is a copy action. */
    fun isCopyClick(texts: List<CharSequence?>, contentDescription: CharSequence?): Boolean {
        val labels = (texts + contentDescription).map(::normalize).filter { it.isNotEmpty() && it.length <= 40 }
        return labels.any { it in copyActions }
    }

    /**
     * A text-selection toolbar / context menu that offers "Copy". On recent Android versions the
     * toolbar is rendered by SystemUI and tapping "Copy" emits no click event, so the engine arms
     * on the toolbar appearing and reads once it closes.
     */
    fun isSelectionMenu(texts: List<CharSequence?>): Boolean =
        texts.size in 2..12 && texts.map(::normalize).any { it in copyActions }

    /** A toast/snackbar/notification text confirming a copy. */
    fun isCopiedConfirmation(texts: List<CharSequence?>): Boolean =
        texts.any { text -> text != null && text.length <= 120 && copiedConfirmations.containsMatchIn(text) }

    /** The system clipboard overlay (Android 13+ preview / Samsung clipboard toast window). */
    fun isClipboardOverlay(packageName: CharSequence?, className: CharSequence?): Boolean {
        val pkg = packageName?.toString() ?: return false
        val cls = className?.toString()?.lowercase(Locale.ROOT) ?: ""
        return (pkg == "com.android.systemui" || pkg.startsWith("com.samsung.android") || pkg == "android") &&
            cls.contains("clipboard")
    }

    /**
     * ClipboardService refuses to notify a background app about a clipboard change and logs it.
     * AOSP: `E ClipboardService: Denying clipboard access to <pkg>, application is not in focus …`.
     * Vendor builds word it slightly differently, so match loosely but require our package name.
     */
    fun isClipboardDenial(logLine: String, packageName: String): Boolean {
        if (!logLine.contains(packageName)) return false
        val lower = logLine.lowercase(Locale.ROOT)
        return lower.contains("clipboard") && (lower.contains("denying") || lower.contains("not in focus") || lower.contains("denied"))
    }

    /** Parses the leading epoch timestamp of a `logcat -v epoch` line, in milliseconds. */
    fun epochMillis(logLine: String): Long? =
        logLine.trimStart().substringBefore(' ').toDoubleOrNull()?.let { (it * 1000).toLong() }
}
