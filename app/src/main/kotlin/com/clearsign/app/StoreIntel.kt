package com.clearsign.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * What the phone itself knows about the dApp that is asking: which package it
 * is, whether it came from the Solana dApp Store, its version, when it was
 * installed and last updated. No network, no way to spoof from a web page —
 * Android tells us who started the activity.
 */
data class StoreInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstall: Long,
    val lastUpdate: Long,
    val installer: String?,
) {
    val fromDappStore: Boolean get() = installer?.let { it.contains("solanamobile", true) || it.contains("dappstore", true) } == true
    val fromPlay: Boolean get() = installer == "com.android.vending"
    /** True when the app has only ever had this one build on the device. */
    val neverUpdated: Boolean get() = lastUpdate - firstInstall < 60_000L
}

object StoreIntel {
    fun of(ctx: Context, packageName: String?): StoreInfo? {
        if (packageName.isNullOrBlank()) return null
        val pm = ctx.packageManager
        return runCatching {
            val info = pm.getPackageInfo(packageName, 0)
            val installer = runCatching { pm.getInstallSourceInfo(packageName).installingPackageName }.getOrNull()
            StoreInfo(
                packageName = packageName,
                label = pm.getApplicationLabel(info.applicationInfo!!).toString(),
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                firstInstall = info.firstInstallTime,
                lastUpdate = info.lastUpdateTime,
                installer = installer,
            )
        }.getOrNull()
    }
}
