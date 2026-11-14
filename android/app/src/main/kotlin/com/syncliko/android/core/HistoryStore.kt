package com.syncliko.android.core

import android.content.Context
import android.util.Log
import com.syncliko.protocol.Clip
import com.syncliko.protocol.ClipAssembler
import com.syncliko.protocol.ClipHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Recent clips, persisted across restarts. Metadata lives in `history/index.json`, contents in one
 * file per clip. Everything is stored, including clips the source app marked as sensitive.
 */
class HistoryStore(context: Context) {
    @Serializable
    data class Entry(val header: ClipHeader, val received: Boolean, val at: Long)

    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): List<Pair<Entry, Clip>> = runCatching {
        if (!index.exists()) return emptyList()
        json.decodeFromString<List<Entry>>(index.readText()).mapNotNull { entry ->
            if (!entry.header.id.matches(ClipAssembler.ID_PATTERN)) return@mapNotNull null
            val file = File(dir, entry.header.id)
            if (!file.exists()) null else entry to Clip(entry.header, file.readBytes())
        }
    }.getOrElse {
        Log.w("SynclikoHistory", "can't load history", it)
        emptyList()
    }

    @Synchronized
    fun save(items: List<Pair<Entry, Clip>>) {
        runCatching {
            items.forEach { (entry, clip) ->
                // Ids come from the peer; never let one become a path.
                if (!entry.header.id.matches(ClipAssembler.ID_PATTERN)) return@forEach
                val file = File(dir, entry.header.id)
                if (!file.exists()) file.writeBytes(clip.content)
            }
            val keep = items.map { it.first.header.id }.toSet() + index.name
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(items.map { it.first }))
            tmp.renameTo(index)
        }.onFailure { Log.w("SynclikoHistory", "can't save history", it) }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
