package com.syncliko.android.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.syncliko.protocol.Endpoint
import com.syncliko.protocol.base64UrlDecode
import com.syncliko.protocol.base64UrlEncode
import com.syncliko.transport.HostPort
import com.syncliko.transport.MacTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Small persistent settings (SharedPreferences + JSON). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("syncliko", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SavedEndpoint(val host: String, val port: Int, val kind: String)

    @Serializable
    private data class SavedMac(val deviceId: String, val name: String, val fingerprint: String, val endpoints: List<SavedEndpoint>)

    val deviceId: String =
        prefs.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString()).also { prefs.edit().putString("deviceId", it).apply() }

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    var pairedMac: MacTarget?
        get() = prefs.getString("mac", null)?.let { raw ->
            runCatching {
                val saved = json.decodeFromString(SavedMac.serializer(), raw)
                MacTarget(
                    deviceId = saved.deviceId,
                    name = saved.name,
                    fingerprint = base64UrlDecode(saved.fingerprint) ?: return@runCatching null,
                    endpoints = saved.endpoints.map { HostPort(it.host, it.port, Endpoint.Kind.valueOf(it.kind)) },
                )
            }.getOrNull()
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove("mac").apply()
                return
            }
            val saved = SavedMac(
                value.deviceId,
                value.name,
                base64UrlEncode(value.fingerprint),
                value.endpoints.map { SavedEndpoint(it.host, it.port, it.kind.name) },
            )
            prefs.edit().putString("mac", json.encodeToString(SavedMac.serializer(), saved)).apply()
        }

    var syncPaused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    var autoCapture: Boolean
        get() = prefs.getBoolean("autoCapture", true)
        set(value) = prefs.edit().putBoolean("autoCapture", value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamicColor", false)
        set(value) = prefs.edit().putBoolean("dynamicColor", value).apply()

    var autostartAcknowledged: Boolean
        get() = prefs.getBoolean("autostartAck", false)
        set(value) = prefs.edit().putBoolean("autostartAck", value).apply()

    var sendOnOpen: Boolean
        get() = prefs.getBoolean("sendOnOpen", true)
        set(value) = prefs.edit().putBoolean("sendOnOpen", value).apply()
}
