package com.armsx.flashpoint.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * User-chosen boot intro and music, after ARMSX2's BootIntro and LibraryMusic custom tracks
 * (ARMSX2 team): the picked file is COPIED into app storage instead of kept as a content URI,
 * so it survives the grant expiring, the source moving and an SD card that is not mounted yet.
 * The file is the setting (each process, the boot screen and the game process included, only
 * asks whether it exists); the display name is for the Config row.
 */
object CustomMedia {
    enum class Kind(val dir: String, val maxBytes: Long, val label: String) {
        BOOT_INTRO("bootintro", 200L * 1024 * 1024, "Boot intro"),
        UI_MUSIC("uimusic", 60L * 1024 * 1024, "Launcher music"),
        PAUSE_MUSIC("pausemusic", 60L * 1024 * 1024, "Pause menu music"),
    }

    enum class Result { OK, TOO_LARGE, UNREADABLE }

    fun file(ctx: Context, kind: Kind): File = File(File(ctx.filesDir, kind.dir).apply { mkdirs() }, "media")

    fun has(ctx: Context, kind: Kind): Boolean = file(ctx, kind).length() > 0L

    /** The chosen file's name, or null when the built-in one is in use. */
    fun name(ctx: Context, kind: Kind): String? =
        if (has(ctx, kind)) prefs(ctx).getString("custom.${kind.dir}", null) ?: "Custom" else null

    /** Copy [uri] in place of [kind]'s file. Off the main thread: an intro can be large. */
    fun set(ctx: Context, kind: Kind, uri: Uri): Result {
        val target = file(ctx, kind)
        val temp = File(target.parentFile, "media.part")
        var tooLarge = false
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                temp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > kind.maxBytes) { tooLarge = true; break }
                        out.write(buf, 0, n)
                    }
                }
            } != null
        }.getOrDefault(false)
        if (tooLarge) { temp.delete(); return Result.TOO_LARGE }
        if (!ok || temp.length() == 0L) { temp.delete(); return Result.UNREADABLE }
        target.delete()
        if (!temp.renameTo(target)) { temp.delete(); return Result.UNREADABLE }
        prefs(ctx).edit().putString("custom.${kind.dir}", displayName(ctx, uri)).apply()
        return Result.OK
    }

    fun clear(ctx: Context, kind: Kind) {
        file(ctx, kind).delete()
        prefs(ctx).edit().remove("custom.${kind.dir}").apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Custom"
}
