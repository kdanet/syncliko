package com.syncliko.android.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.syncliko.protocol.Endpoint
import com.syncliko.protocol.SynclikoProtocol
import com.syncliko.transport.HostPort
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Finds the paired Mac via Bonjour (`_syncliko._tcp`) and remembers its latest address.
 * Runs only while disconnected to save battery.
 */
class NsdDiscovery(context: Context, private val macDeviceId: () -> String?) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: NsdManager.DiscoveryListener? = null

    @Volatile var latest: HostPort? = null
        private set

    var onFound: (() -> Unit)? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                synchronized(this@NsdDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        listener = l
        runCatching { nsd.discoverServices(SynclikoProtocol.BONJOUR_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    accept(resolved)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) = accept(resolved)
            })
        }
    }

    private fun accept(info: NsdServiceInfo) {
        val id = info.attributes["id"]?.decodeToString()
        if (id == null || id != macDeviceId()) return
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.host
        } ?: return
        val found = HostPort(address.hostAddress ?: return, info.port, Endpoint.Kind.BONJOUR)
        if (found != latest) {
            Log.i(TAG, "found Mac at $found")
            latest = found
            onFound?.invoke()
        }
    }

    private companion object {
        const val TAG = "SynclikoNsd"
    }
}
