package com.armsx.flashpoint.game

import com.armsx.flashpoint.core.Game

/** How a game runs on Android. Desktop launch info is mapped onto a native runner. */
enum class Runner(val label: String, val ready: Boolean) {
    /** A .swf, played by Ruffle. */
    FLASH("Flash (Ruffle)", true),
    /** A web page: HTML5, or a page embedding Flash (Ruffle replaces the plugin in-page). */
    WEB("Browser", true),
    SHOCKWAVE("Shockwave", false),
    JAVA("Java", false),
    UNITY("Unity Web Player", false),
    SILVERLIGHT("Silverlight", false),
    OTHER("Unsupported", false),
}

data class LaunchPlan(val runner: Runner, val url: String)

object Launch {
    private val urlRe = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)

    fun plan(game: Game): LaunchPlan {
        val app = game.applicationPath.replace('\\', '/').lowercase()
        val cmd = game.launchCommand
        // The desktop passes the whole command as one argument, so a URL may contain spaces
        // ("games/Hidden Room Escape.swf"); a quoted one ends at its closing quote.
        val t = cmd.trim()
        val raw = when {
            t.startsWith("\"") -> t.drop(1).substringBefore('"')
            t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
            else -> urlRe.find(cmd)?.value ?: t
        }
        val url = raw.trim().replace(" ", "%20")
        val path = url.substringBefore('?').lowercase()
        val platform = game.primaryPlatform.lowercase()

        val runner = when {
            path.endsWith(".swf") -> Runner.FLASH
            platform == "flash" && ("flashplayer" in app || "ruffle" in app) -> Runner.FLASH
            platform == "shockwave" || path.endsWith(".dcr") || path.endsWith(".dir") -> Runner.SHOCKWAVE
            platform == "java" -> Runner.JAVA
            platform == "unity" || path.endsWith(".unity3d") -> Runner.UNITY
            platform == "silverlight" || path.endsWith(".xap") -> Runner.SILVERLIGHT
            app == ":browser-mode:" || platform == "html5" -> Runner.WEB
            // Browser-hosted plugin content (Basilisk / FPNavigator): a page, Flash inside it
            // is picked up by Ruffle.
            ("basilisk" in app || "fpnavigator" in app || "firefox" in app) && platform == "flash" -> Runner.WEB
            else -> Runner.OTHER
        }
        return LaunchPlan(runner, url)
    }
}
