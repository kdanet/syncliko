package com.syncliko.android.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.syncliko.protocol.Clip
import com.syncliko.protocol.ClipKind
import com.syncliko.protocol.SynclikoProtocol
import java.io.File

/**
 * Reads and writes the system clipboard.
 *
 * Writing works from the background. Reading works only while one of our windows has focus
 * (Android 10+), so callers read from an activity: [com.syncliko.android.capture.SendActivity] or
 * the main screen.
 */
class ClipboardBridge(private val context: Context, private val deviceId: String) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val clipsDir = File(context.cacheDir, "clips")

    companion object {
        const val ORIGIN_EXTRA = "com.syncliko.origin"
        private const val LABEL = "Syncliko"
    }

    /** Puts a clip received from the Mac on the clipboard, tagged with its origin. */
    fun write(clip: Clip) {
        val data = when (clip.kind) {
            ClipKind.TEXT, ClipKind.URL -> ClipData.newPlainText(LABEL, clip.text.orEmpty())
            ClipKind.IMAGE -> ClipData.newUri(context.contentResolver, LABEL, saveImage(clip))
        }
        data.description.extras = PersistableBundle().apply {
            putString(ORIGIN_EXTRA, clip.header.origin)
            if (clip.header.sensitive) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(data)
    }

    /** Current clipboard as a clip, or null if empty, ours, or unreadable (not in focus). */
    fun read(): Clip? = snapshot()?.let(::toClip)

    /**
     * Grabs the raw ClipData — the only part that needs input focus. Cheap; safe on the main thread.
     * Converting it ([toClip]) may read image streams and belongs on a background thread.
     */
    fun snapshot(): ClipData? = runCatching { clipboard.primaryClip }.getOrNull()

    fun toClip(data: ClipData): Clip? {
        val description = data.description
        if (description.extras?.containsKey(ORIGIN_EXTRA) == true) return null
        if (data.itemCount == 0) return null
        val sensitive = description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val item = data.getItemAt(0)
        item.uri?.let { uri -> readImage(uri)?.let { return it } }
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return null
        return Clip.text(text, deviceId, sensitive)
    }

    private fun readImage(uri: Uri): Clip? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: return null
        if (!mime.startsWith("image/")) return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = input.readNBytes(SynclikoProtocol.MAX_CLIP_SIZE + 1)
            if (buffer.size > SynclikoProtocol.MAX_CLIP_SIZE) return null
            buffer
        } ?: return null
        Clip.make(ClipKind.IMAGE, mime, bytes, deviceId)
    }.getOrNull()

    fun imageClip(uri: Uri): Clip? = readImage(uri)

    private fun saveImage(clip: Clip): Uri {
        clipsDir.mkdirs()
        // Keep only the most recent few images.
        clipsDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val ext = when (clip.header.mime) {
            "image/jpeg" -> "jpg"
            "image/heic" -> "heic"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "png"
        }
        val file = File(clipsDir, "clip-${clip.id.take(8)}.$ext")
        file.writeBytes(clip.content)
        return FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
    }
}
