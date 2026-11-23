package com.armsx.flashpoint.game

/**
 * Games run from their own live website, with their Flashpoint pack as the fallback when the site
 * cannot be reached: their online parts only work with the site's current version. Happy Wheels
 * HTML5: the pack's 2021 client and today's level server no longer fully understand each other
 * (Featured came back empty and levels never finished building); the site's own client does.
 */
object LiveSites {
    private val FIRST = mapOf(
        "b448cbec-8a0e-4abf-96cf-4a12b41b6818" to listOf("totaljerkface.com"), // Happy Wheels (HTML5)
    )

    /** The sites [gameId] runs from, marked for the game server ("!site": live before the pack). */
    fun firstFor(gameId: String): List<String> = FIRST[gameId].orEmpty().map { "!$it" }

    /**
     * Where [gameId] starts: over https when it runs from its live site, as the site itself does
     * today. Happy Wheels' current code only runs from an https://totaljerkface.com address and
     * otherwise loops forever (a blue page); desktop Chrome hid that by upgrading http on its own.
     */
    fun startUrl(gameId: String, url: String): String =
        if (gameId in FIRST && url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
}
