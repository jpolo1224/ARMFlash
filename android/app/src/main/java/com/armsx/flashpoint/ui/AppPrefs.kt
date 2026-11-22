package com.armsx.flashpoint.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * The launcher's Config options that apply on Android, under the desktop names, plus
 * ARMFlash's own (music, touch, Legacy online). Each is Compose state backed by prefs.
 */
class AppPrefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = Pref(key, mutableStateOf(p.getBoolean(key, def))) { k, v -> p.edit().putBoolean(k, v).apply() }
    private fun str(key: String, def: String) = Pref(key, mutableStateOf(p.getString(key, def)!!)) { k, v -> p.edit().putString(k, v).apply() }
    private fun strs(key: String, def: Set<String>) = Pref(key, mutableStateOf(p.getStringSet(key, def)!!.toSet())) { k, v -> p.edit().putStringSet(k, v).apply() }

    // Preferences
    val extremeGames = bool("extremeGames", false)
    val hideExtremeScreenshots = bool("hideExtremeScreenshots", true)
    val defaultOpeningPage = str("defaultOpeningPage", "home")

    // On Demand Images
    val onDemandImages = bool("onDemandImages", true)
    val onDemandImagesCompressed = bool("onDemandImagesCompressed", true)

    // Playtime Tracking
    val enablePlaytimeTracking = bool("enablePlaytimeTracking", true)
    val enablePlaytimeTrackingExtreme = bool("enablePlaytimeTrackingExtreme", true)

    // Advanced
    val enableVerboseLogging = bool("enableVerboseLogging", false)

    // Flashpoint
    val randomLibraries = strs("randomLibraries", setOf("arcade"))

    // Flash: "native" (Ruffle in Rust, Vulkan / OpenGL ES) or "web" (Ruffle's web build in the browser).
    val flashPlayer = str("flashPlayer", "native")
    // Graphics for native Flash: "auto", "vulkan", "gl".
    val flashBackend = str("flashBackend", "auto")

    val bootAnimation = bool("bootAnimation", true)

    // Visuals: 0 = Off, 1 = On (long press), 2 = Always
    val screenshotPreviewMode = str("screenshotPreviewMode", "0")

    class Pref<T>(private val key: String, val state: MutableState<T>, private val save: (String, T) -> Unit) {
        var value: T
            get() = state.value
            set(v) {
                state.value = v
                save(key, v)
            }
    }
}
