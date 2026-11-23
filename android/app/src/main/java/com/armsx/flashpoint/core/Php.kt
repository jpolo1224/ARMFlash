package com.armsx.flashpoint.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * PHP for the game server's PHP pages, as desktop Flashpoint's Legacy server runs them: PHP 7.4
 * as CGI (tools/winrt/packphp.py), shipped in the APK as assets/php.tar.zst with its own glibc,
 * and started through the APK's glibc loader, so it needs nothing else installed. Unpacked into
 * the app's storage the first time a game starts.
 */
object Php {
    private const val TAG = "ARMFlash"
    private const val ASSET = "php.tar.zst"

    /** Unpack PHP if needed and tell the game server about it. Call before games start. */
    @Synchronized
    fun ensure(ctx: Context) {
        runCatching {
            val home = File(ctx.dataDir, "php")
            val mark = File(home, ".armflash-php")
            // Unpacked again after every app update (the APK may carry a newer PHP).
            val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
            if (mark.readTextOrNull() != stamp) {
                val tmp = File(ctx.cacheDir, ASSET)
                ctx.assets.open(ASSET).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                home.deleteRecursively()
                FpCore.untarZst(tmp.path, ctx.dataDir.path)
                tmp.delete()
                mark.writeText(stamp)
                Log.i(TAG, "php: unpacked")
            }
            val loader = File(ctx.applicationInfo.nativeLibraryDir, "libldlinux.so")
            FpCore.setPhp(loader.path, home.path, File(ctx.cacheDir, "php").path)
        }.onFailure { Log.w(TAG, "php: ${it.message}") }
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()
}
