package com.syncliko.protocol

import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** A complete clipboard item (header + full content). */
class Clip(val header: ClipHeader, val content: ByteArray) {
    val id: String get() = header.id
    val kind: ClipKind get() = header.kind
    val text: String? get() = if (kind == ClipKind.IMAGE) null else content.decodeToString()

    /** Splits the clip into a `clip` message followed by `clipChunk` messages (PROTOCOL.md §4). */
    fun messages(chunkSize: Int = SynclikoProtocol.CHUNK_SIZE): List<Message> {
        val result = mutableListOf<Message>()
        var offset = 0
        do {
            val end = minOf(offset + chunkSize, content.size)
            val slice = content.copyOfRange(offset, end)
            result += if (offset == 0) Message.ClipMsg(header, slice) else Message.ClipChunkMsg(ClipChunk(header.id, offset), slice)
            offset = end
        } while (offset < content.size)
        return result
    }

    override fun equals(other: Any?) = other is Clip && header == other.header && content.contentEquals(other.content)
    override fun hashCode() = header.hashCode() * 31 + content.contentHashCode()
    override fun toString() = "Clip(${header.kind}, ${content.size} B, ${header.id})"

    companion object {
        fun make(
            kind: ClipKind,
            mime: String,
            content: ByteArray,
            origin: String,
            sensitive: Boolean = false,
            id: String = UUID.randomUUID().toString().uppercase(),
            createdAt: Long = System.currentTimeMillis(),
        ) = Clip(
            ClipHeader(
                createdAt = createdAt,
                id = id,
                kind = kind,
                mime = mime,
                origin = origin,
                sensitive = sensitive,
                sha256 = content.sha256Hex(),
                size = content.size,
            ),
            content,
        )

        fun text(text: String, origin: String, sensitive: Boolean = false): Clip {
            val isUrl = text.isSingleWebUrl()
            return make(
                kind = if (isUrl) ClipKind.URL else ClipKind.TEXT,
                mime = if (isUrl) "text/uri-list" else "text/plain;charset=utf-8",
                content = text.encodeToByteArray(),
                origin = origin,
                sensitive = sensitive,
            )
        }
    }
}

class ClipAssemblyException(message: String) : Exception(message)

/** Reassembles chunked clips from incoming messages. Not thread-safe. */
class ClipAssembler(private val timeoutMs: Long = 60_000) {
    companion object {
        const val MAX_PARTIALS = 2
        /** Clip ids end up in file names on the receiving side: keep them boring. */
        val ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")
    }

    private class Partial(val header: ClipHeader, val data: ByteArrayOutputStream, val startedAt: Long)

    private val partials = HashMap<String, Partial>()

    fun begin(header: ClipHeader, firstChunk: ByteArray, now: Long = System.currentTimeMillis()): Clip? {
        if (header.size !in 0..SynclikoProtocol.MAX_CLIP_SIZE) throw ClipAssemblyException("bad size: ${header.size}")
        if (!header.id.matches(ID_PATTERN)) throw ClipAssemblyException("bad id")
        if (firstChunk.size > minOf(header.size, SynclikoProtocol.CHUNK_SIZE)) throw ClipAssemblyException("chunk larger than clip")
        partials.values.removeAll { now - it.startedAt > timeoutMs }
        while (partials.size >= MAX_PARTIALS) partials.remove(partials.minBy { it.value.startedAt }.key)
        // Don't trust the claimed size for allocation; grow as data actually arrives.
        val partial = Partial(header, ByteArrayOutputStream(minOf(header.size, SynclikoProtocol.CHUNK_SIZE)), now)
        partial.data.write(firstChunk)
        return finishIfComplete(partial)
    }

    fun append(chunk: ClipChunk, data: ByteArray): Clip? {
        val partial = partials[chunk.id]
        if (partial == null || partial.data.size() != chunk.offset || data.size > SynclikoProtocol.CHUNK_SIZE ||
            partial.data.size() + data.size > partial.header.size
        ) {
            partials.remove(chunk.id)
            throw ClipAssemblyException("unexpected chunk ${chunk.id}@${chunk.offset}")
        }
        partial.data.write(data)
        return finishIfComplete(partial)
    }

    private fun finishIfComplete(partial: Partial): Clip? {
        val id = partial.header.id
        if (partial.data.size() < partial.header.size) {
            partials[id] = partial
            return null
        }
        partials.remove(id)
        val bytes = partial.data.toByteArray()
        if (bytes.size != partial.header.size || bytes.sha256Hex() != partial.header.sha256) {
            throw ClipAssemblyException("hash mismatch $id")
        }
        return Clip(partial.header, bytes)
    }
}

fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
fun ByteArray.sha256Hex(): String = sha256().toHex()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** True when the trimmed string is exactly one http(s) URL. */
fun String.isSingleWebUrl(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
    return try {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }
}
