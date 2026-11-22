package com.armsx.flashpoint.shaders

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Which RetroArch preset native Flash runs, and the parameters changed in it. Config sets the
 * preset for every game; the pause menu can override it for one game ("" = same as Config,
 * "off" = none). Parameter changes are kept per preset, like ARMSX2's shaderChainParams.
 * Presets are stored relative to the shaders folder, so a moved data folder keeps them.
 */
object ShaderPrefs {
    const val OFF = "off"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun globalPreset(ctx: Context) = prefs(ctx).getString("shader.preset", "")!!
    /** Config's RetroArch Shaders switch (ARMSX2's shaderChainEnabled). */
    fun globalEnabled(ctx: Context) = prefs(ctx).getBoolean("shader.enabled", false)
    fun setGlobalEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shader.enabled", on).apply()

    /** Config's preset as an absolute path ("" for none), for ARMSX2's picker. */
    fun globalPresetPath(ctx: Context): String =
        globalPreset(ctx).takeIf { it.isNotEmpty() && it != OFF }?.let { java.io.File(ShaderRepo.shadersRoot(ctx), it).path }.orEmpty()

    fun setGlobalPresetPath(ctx: Context, abs: String) {
        val root = ShaderRepo.shadersRoot(ctx).path.trimEnd('/') + "/"
        setGlobalPreset(ctx, if (abs.isBlank()) "" else abs.removePrefix(root))
    }

    /** Every preset's changed parameters (absolute preset path to name to value). */
    fun allOverrides(ctx: Context): Map<String, Map<String, Float>> {
        val a = all(ctx)
        return a.keys().asSequence().associateWith { k -> overrides(ctx, k) }
    }

    fun setAllOverrides(ctx: Context, map: Map<String, Map<String, Float>>) {
        val a = JSONObject()
        map.forEach { (preset, values) -> a.put(preset, JSONObject().apply { values.forEach { (k, v) -> put(k, v.toDouble()) } }) }
        prefs(ctx).edit().putString("shader.params", a.toString()).apply()
    }
    fun gamePreset(ctx: Context, id: String) = prefs(ctx).getString("shader.preset.$id", "")!!
    fun setGlobalPreset(ctx: Context, rel: String) = prefs(ctx).edit().putString("shader.preset", rel).apply()
    fun setGamePreset(ctx: Context, id: String, rel: String) = prefs(ctx).edit().putString("shader.preset.$id", rel).apply()

    /** The preset file this game runs, or null for none. */
    fun presetFile(ctx: Context, id: String): File? {
        val rel = gamePreset(ctx, id).ifEmpty { if (globalEnabled(ctx)) globalPreset(ctx) else "" }
        if (rel.isEmpty() || rel == OFF) return null
        return File(ShaderRepo.shadersRoot(ctx), rel).takeIf { it.isFile }
    }

    private fun all(ctx: Context) = runCatching { JSONObject(prefs(ctx).getString("shader.params", "{}")!!) }.getOrDefault(JSONObject())

    /** Changed parameters of [preset] (its absolute path). */
    fun overrides(ctx: Context, preset: String): Map<String, Float> {
        val o = all(ctx).optJSONObject(preset) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    }

    fun setOverrides(ctx: Context, preset: String, values: Map<String, Float>) {
        val a = all(ctx)
        a.put(preset, JSONObject().apply { values.forEach { (k, v) -> put(k, v.toDouble()) } })
        prefs(ctx).edit().putString("shader.params", a.toString()).apply()
    }

    /** What native Flash reads: one "name value" line per changed parameter. */
    fun paramLines(ctx: Context, preset: String): String =
        overrides(ctx, preset).entries.joinToString("\n") { "${it.key} ${it.value}" }
}
