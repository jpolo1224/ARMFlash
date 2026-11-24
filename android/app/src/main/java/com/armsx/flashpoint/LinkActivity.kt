package com.armsx.flashpoint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Where games from outside arrive: frontends' `armflash://play/<id>` links, `.armflash` files and a
 * `.swf` opened with ARMFlash. It passes each one on at once and is gone, drawing nothing.
 *
 * These used to open the launcher screen (BootActivity) directly, and Android brings an existing
 * task to the front without delivering anything when it is started by the same intent that started
 * that task: a frontend launching the same game twice while ARMFlash was open only showed the
 * library. This activity keeps no task of its own (no affinity, no history), so each link reaches
 * it, and it hands the game to the open library, or starts ARMFlash with it.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = Frontend.gameId(this, intent)
        val next = when {
            // That game is running already: show it as it is (the launcher screen, finding ARMFlash
            // open, just steps aside).
            id != null && MainActivity.alive > 0 && id == MainActivity.playing ->
                Intent(this, BootActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // ARMFlash is open: straight to its library, which plays the game (no intro), closing
            // another game that is running.
            id != null && MainActivity.alive > 0 -> Intent(this, MainActivity::class.java).putExtra("gameId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Not open: the intro, then the game.
            id != null -> Intent(this, BootActivity::class.java).putExtra("gameId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // A .swf for My Games: copied here, while this activity holds the access the sender
            // gave it (passing that access on is refused), and the launcher screen adds the copy.
            else -> copySwf()?.let { (file, name) ->
                Intent(this, BootActivity::class.java).putExtra(BootActivity.EXTRA_SWF, file.path).putExtra(BootActivity.EXTRA_SWF_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: Intent(this, BootActivity::class.java).setData(intent.data)
                // Some other file: the launcher screen says what ARMFlash opens.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(next) }.onFailure { Log.w("LinkActivity", "could not open ${intent.data}: $it") }
        finish()
    }

    /** The .swf this was opened with, copied into the cache, and its name; null when it is not one. */
    private fun copySwf(): Pair<java.io.File, String>? = runCatching {
        val uri = Frontend.swf(this, intent) ?: return null
        val name = com.armsx.flashpoint.ui.displayName(this, uri)
        val file = java.io.File(cacheDir, "incoming-${System.nanoTime()}.swf")
        contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        file to (name ?: file.name)
    }.onFailure { Log.w("LinkActivity", "could not read ${intent.data}: $it") }.getOrNull()
}
