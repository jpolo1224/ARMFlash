package com.armsx.flashpoint

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Frontend support (ES-DE, Daijisho, Beacon, launcher shortcuts), as ARMSX2 offers it for
 * PS2 files. Flashpoint games are not files, so a game is addressed by its Flashpoint ID:
 *  - `armflash://play/<id>` links;
 *  - `.armflash` files, one small text file per game (`id=<id>` on a line), that frontends
 *    scan like ROMs and open with ARMFlash (Config, Frontends exports them);
 *  - an `id` or `gameId` extra.
 */
object Frontend {
    const val SCHEME = "armflash"
    const val EXT = "armflash"

    fun playUri(id: String): Uri = Uri.parse("$SCHEME://play/$id")

    /** The Flashpoint game an outside launch asks for, or null. */
    fun gameId(ctx: Context, intent: Intent?): String? {
        intent ?: return null
        (intent.getStringExtra("id") ?: intent.getStringExtra("gameId"))?.takeIf { looksLikeId(it) }?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme == SCHEME) return data.lastPathSegment?.takeIf { looksLikeId(it) }
        if (data.scheme == "content" || data.scheme == "file") {
            val text = runCatching {
                ctx.contentResolver.openInputStream(data)?.use { it.readBytes().take(4096).toByteArray().decodeToString() }
            }.getOrNull() ?: return null
            return text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("id=") }?.removePrefix("id=")?.takeIf { looksLikeId(it) }
        }
        return null
    }

    private fun looksLikeId(s: String) = Regex("""[0-9a-fA-F-]{36}""").matches(s.trim())

    /** The text of a game's `.armflash` file. */
    fun fileText(id: String, title: String, platform: String) =
        "# ARMFlash game: open with ARMFlash\nid=$id\ntitle=$title\nplatform=$platform\n"

    /** A file name safe on every file system, "Title [Platform].armflash". */
    fun fileName(title: String, platform: String): String {
        val clean = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().take(120)
        return "$clean [$platform].$EXT"
    }
}
