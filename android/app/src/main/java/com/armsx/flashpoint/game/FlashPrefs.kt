package com.armsx.flashpoint.game

import android.content.Context

/**
 * Flash's player (native Ruffle or Ruffle in the browser) and native Ruffle's graphics API:
 * Config sets them for every game, the pause menu can override them for one game.
 */
object FlashPrefs {
    val players = listOf("" to "Same as Config", "native" to "Native", "web" to "Browser", "flashplayer" to "Flash Player")
    val backends = listOf("" to "Same as Config", "auto" to "Auto", "vulkan" to "Vulkan", "gl" to "OpenGL ES")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun gamePlayer(ctx: Context, id: String) = prefs(ctx).getString("flashPlayer.$id", "")!!
    fun gameBackend(ctx: Context, id: String) = prefs(ctx).getString("flashBackend.$id", "")!!

    fun setGamePlayer(ctx: Context, id: String, v: String) = prefs(ctx).edit().putString("flashPlayer.$id", v).apply()
    fun setGameBackend(ctx: Context, id: String, v: String) = prefs(ctx).edit().putString("flashBackend.$id", v).apply()

    /** "native" or "web" for this game. */
    fun player(ctx: Context, id: String, page: Boolean = false) = gamePlayer(ctx, id).ifEmpty {
        // A Flash game desktop Flashpoint runs as a page in its browser: its browser with the
        // Flash plugin, as there (Ruffle in a page leaves some, like Poptropica, at a blank page).
        if (page) "flashplayer" else prefs(ctx).getString("flashPlayer", "native")!!
    }

    /** "auto", "vulkan" or "gl" for this game. */
    fun backend(ctx: Context, id: String) = gameBackend(ctx, id).ifEmpty { prefs(ctx).getString("flashBackend", "auto")!! }

    /** The activity a Flash game runs in with its current settings. */
    fun activity(ctx: Context, id: String): Class<*> = when (player(ctx, id)) {
        "native" -> RuffleActivity::class.java
        "flashplayer" -> com.armsx.flashpoint.win.WinActivity::class.java
        else -> GameActivity::class.java
    }
}
