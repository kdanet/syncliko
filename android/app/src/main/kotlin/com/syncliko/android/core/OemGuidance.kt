package com.syncliko.android.core

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Vendor skins kill background apps far more aggressively than stock Android. This maps the device's
 * manufacturer to the screens where the user can let Syncliko run (autostart, background activity,
 * "never sleeping" lists). Component names change between OS versions, so every target is resolved
 * before launching and falls back to App info. Background: dontkillmyapp.com.
 */
object OemGuidance {
    enum class Vendor { SAMSUNG, XIAOMI, HUAWEI, HONOR, OPPO, VIVO, OTHER }

    val vendor: Vendor by lazy {
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> Vendor.SAMSUNG
            "xiaomi", "redmi", "poco" -> Vendor.XIAOMI
            "huawei" -> Vendor.HUAWEI
            "honor" -> Vendor.HONOR
            "oppo", "realme", "oneplus" -> Vendor.OPPO
            "vivo", "iqoo" -> Vendor.VIVO
            else -> Vendor.OTHER
        }
    }

    /** True when this vendor has an extra autostart/background screen beyond Android's battery setting. */
    val hasAutostartScreen: Boolean get() = vendor in setOf(Vendor.XIAOMI, Vendor.HUAWEI, Vendor.HONOR, Vendor.OPPO, Vendor.VIVO)

    private val autostartTargets: Map<Vendor, List<ComponentName>> = mapOf(
        Vendor.XIAOMI to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        ),
        Vendor.HUAWEI to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        Vendor.HONOR to listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ),
        Vendor.OPPO to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        ),
        Vendor.VIVO to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ),
    )

    /** Opens the vendor's autostart / background screen, or App info if none resolves. */
    fun openAutostart(context: Context) {
        val pm = context.packageManager
        val target = autostartTargets[vendor].orEmpty().firstOrNull { component ->
            runCatching { pm.getActivityInfo(component, 0) }.isSuccess
        }
        val intent = target?.let { Intent().setComponent(it) } ?: appDetails(context)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** "Restricted" battery mode (Android 9+): no foreground service can start at all. */
    fun isBackgroundRestricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
}
