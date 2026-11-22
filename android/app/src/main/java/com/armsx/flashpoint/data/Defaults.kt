package com.armsx.flashpoint.data

import android.content.Context
import java.io.File

/**
 * What a desktop Flashpoint install brings in its Data folder, bundled so ARMFlash has it from
 * the start: credits.json (the About page's contributors), the default platform logos (Logos),
 * the Logo Sets and the themes (their CSS). Copied into the Flashpoint folder wherever it is, a
 * file at a time and never over one that is already there, so a user's own copies win.
 */
object Defaults {
    private const val ASSET_ROOT = "flashpoint/Data"

    fun install(ctx: Context, root: File) {
        runCatching { copyDir(ctx, ASSET_ROOT, File(root, "Data")) }
    }

    private fun copyDir(ctx: Context, asset: String, dest: File) {
        val children = ctx.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            if (dest.exists()) return
            dest.parentFile?.mkdirs()
            ctx.assets.open(asset).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            return
        }
        children.forEach { copyDir(ctx, "$asset/$it", File(dest, it)) }
    }
}
