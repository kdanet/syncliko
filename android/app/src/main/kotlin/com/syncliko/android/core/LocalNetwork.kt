package com.syncliko.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 protects the local network: without ACCESS_LOCAL_NETWORK an app can't open LAN
 * connections, and every Bonjour discovery pops a system device picker. We request it up front.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context): Boolean =
        !isRequired || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
