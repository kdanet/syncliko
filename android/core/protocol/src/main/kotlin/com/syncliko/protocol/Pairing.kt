package com.syncliko.protocol

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Contents of the pairing QR code (PROTOCOL.md §5). */
data class PairingInvite(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val fingerprint: ByteArray,
    val token: ByteArray,
    val port: Int,
    val hosts: List<String>,
) {
    override fun equals(other: Any?) = other is PairingInvite && version == other.version && deviceId == other.deviceId &&
        deviceName == other.deviceName && fingerprint.contentEquals(other.fingerprint) && token.contentEquals(other.token) &&
        port == other.port && hosts == other.hosts

    override fun hashCode() = deviceId.hashCode() * 31 + fingerprint.contentHashCode()

    companion object {
        /** Parses `syncliko://pair?...`. Returns null for anything malformed. */
        fun parse(url: String): PairingInvite? = try {
            val uri = URI(url.trim())
            if (uri.scheme != "syncliko" || uri.host != "pair") {
                null
            } else {
                val q = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
                    val i = it.indexOf('=')
                    val key = if (i < 0) it else it.substring(0, i)
                    val value = if (i < 0) "" else URLDecoder.decode(it.substring(i + 1).replace("+", "%2B"), Charsets.UTF_8)
                    key to value
                }
                val fp = q["fp"]?.let(::base64UrlDecode)
                val token = q["t"]?.let(::base64UrlDecode)
                val port = q["p"]?.toIntOrNull()
                if (q["v"]?.toIntOrNull() == null || q["id"] == null || q["n"] == null || fp?.size != 32 ||
                    token == null || token.isEmpty() || port == null || port !in 1..65535
                ) {
                    null
                } else {
                    PairingInvite(
                        version = q.getValue("v").toInt(),
                        deviceId = q.getValue("id"),
                        deviceName = q.getValue("n"),
                        fingerprint = fp,
                        token = token,
                        port = port,
                        hosts = q["h"].orEmpty().split(',').filter { it.isNotBlank() },
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
fun base64UrlDecode(s: String): ByteArray? = try { Base64.getUrlDecoder().decode(s) } catch (_: IllegalArgumentException) { null }

/**
 * Six-digit code both devices show during pairing so the user can check they talk to each other.
 * SHA-256(macFingerprint ‖ phoneFingerprint), first 4 bytes big-endian, mod 1 000 000 (same as the Mac).
 */
object PairingCode {
    fun make(macFingerprint: ByteArray, phoneFingerprint: ByteArray): String {
        val d = (macFingerprint + phoneFingerprint).sha256()
        val v = ((d[0].toLong() and 0xFF) shl 24 or ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)) % 1_000_000
        val digits = "%06d".format(v)
        return digits.take(3) + " " + digits.takeLast(3)
    }
}
