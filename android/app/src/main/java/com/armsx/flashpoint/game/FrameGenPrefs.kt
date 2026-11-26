package com.armsx.flashpoint.game

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Frame generation's settings for native Flash (the GitHub build's FrameGenUi edits them; the
 * Play build has no frame generation, so nothing there sets them and they stay off).
 *
 * One small JSON file, read fresh every time, instead of the shared "settings" preferences:
 * Config (the main process) and the pause menu (the :game process) both change these, and each
 * process's SharedPreferences keeps its own copy and writes all of it back.
 */
data class FrameGenSettings(
    val enabled: Boolean = false,
    /** Frames shown per rendered frame: 2, 3 or 4. */
    val multiplier: Int = 2,
    /** Motion detail, 25..100 percent; 100 is automatic. */
    val motion: Int = 100,
    /** The half-precision shaders (takes effect at the next game). */
    val fp16: Boolean = false,
    /** Vary the generated frames to hold the screen's refresh rate. */
    val adaptive: Boolean = false,
    /** The user's own shader file, copied into the app's files ("" for none). */
    val dll: String = "",
)

object FrameGenPrefs {
    /** Why frame generation cannot run (the native library's numbers). */
    object Reason {
        const val AVAILABLE = 0
        const val NOT_COMPILED_IN = 1
        const val NOT_VULKAN = 2
        const val GPU_UNSUPPORTED = 3
        const val NO_DLL = 4
        const val DLL_UNREADABLE = 5
        const val INIT_FAILED = 6
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "framegen.json")

    fun load(ctx: Context): FrameGenSettings = runCatching {
        val o = JSONObject(file(ctx).readText())
        FrameGenSettings(
            enabled = o.optBoolean("enabled", false),
            multiplier = o.optInt("multiplier", 2).coerceIn(2, 4),
            motion = o.optInt("motion", 100).coerceIn(25, 100),
            fp16 = o.optBoolean("fp16", false),
            adaptive = o.optBoolean("adaptive", false),
            dll = o.optString("dll", ""),
        )
    }.getOrDefault(FrameGenSettings())

    fun save(ctx: Context, s: FrameGenSettings) {
        val o = JSONObject()
            .put("enabled", s.enabled).put("multiplier", s.multiplier).put("motion", s.motion)
            .put("fp16", s.fp16).put("adaptive", s.adaptive).put("dll", s.dll)
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        runCatching { tmp.writeText(o.toString()); tmp.renameTo(f) }
    }

    /** For the native player: key=value lines, with the screen's refresh rate. */
    fun native(ctx: Context, displayHz: Float): String {
        val s = load(ctx)
        return buildString {
            append("enabled=").append(if (s.enabled) 1 else 0).append('\n')
            append("multiplier=").append(s.multiplier).append('\n')
            append("motion=").append(s.motion).append('\n')
            append("fp16=").append(if (s.fp16) 1 else 0).append('\n')
            append("adaptive=").append(if (s.adaptive) 1 else 0).append('\n')
            append("dll=").append(s.dll).append('\n')
            append("cache=").append(ctx.cacheDir.path).append('\n')
            append("display=").append(displayHz).append('\n')
        }
    }
}
