package com.mozkhan.shop.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Diagnostics that help set up Google sign-in: the SHA-1 of the key THIS installed APK was signed with. */
object Diag {
    @Suppress("DEPRECATION")
    fun signingSha1(ctx: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        }
        val sig = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull() else info.signatures?.firstOrNull()
        sig?.let { s -> MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString(":") { "%02X".format(it) } }.orEmpty()
    }.getOrDefault("")
}
