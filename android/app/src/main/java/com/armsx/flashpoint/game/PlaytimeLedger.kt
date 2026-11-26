package com.armsx.flashpoint.game

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Playtime that survives however a game ends. The game process writes each run's time so far to
 * a small file (every 30 seconds, when the game is left, and once more, marked final, when it
 * closes); the launcher adds up the runs that are over and deletes their files. Before this, time
 * only came back with a game's own Exit, so a game left with Home and closed by Android later, a
 * crash, or the first half of a Restart counted nothing (Elemental showed as never played).
 */
object PlaytimeLedger {
    private fun dir(ctx: Context) = File(ctx.filesDir, "playtime").apply { mkdirs() }

    /** One run of one game, in the game process. */
    class Session(ctx: Context, private val gameId: String) {
        private val file = File(dir(ctx), "${System.currentTimeMillis()}-${android.os.Process.myPid()}")
        private val started = SystemClock.elapsedRealtime()
        private var closed = false

        /** Save the time so far; [final] when the game is closing (nothing is written after that). */
        fun write(final: Boolean) {
            if (closed || gameId.isBlank()) return
            if (final) closed = true
            val secs = (SystemClock.elapsedRealtime() - started) / 1000
            runCatching {
                val tmp = File(file.path + ".part")
                tmp.writeText("$gameId\n$secs\n${if (final) 1 else 0}\n")
                tmp.renameTo(file)
            }
        }
    }

    /**
     * The launcher: every run that is over (closed, or its game process gone), as game id and
     * seconds, each once (its file is deleted). A run whose game is still going is left for later.
     */
    fun collect(ctx: Context, gameRunning: Boolean): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (f in dir(ctx).listFiles().orEmpty()) {
            if (f.name.endsWith(".part")) continue
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            val id = lines.getOrNull(0).orEmpty()
            val secs = lines.getOrNull(1)?.toLongOrNull() ?: 0L
            val final = lines.getOrNull(2) == "1"
            if (!final && gameRunning) continue
            if (f.delete() && id.isNotBlank() && secs > 0) out += id to secs
        }
        return out
    }
}
