package com.armsx.flashpoint.game

import android.app.Activity
import android.hardware.input.InputManager
import android.view.MotionEvent

/**
 * What both game screens (WebView and native Ruffle) do around the game itself: menu sounds and
 * pause music, the sleep and wake chimes, opening the menu when the app is left (as ARMSX2 does,
 * so coming back lands on Resume instead of a silently frozen game), letting go of every held
 * key whenever the game stops getting input, and knowing whether a controller is attached.
 */
class GameScreen(private val act: Activity, private val overlay: OverlayState, private val pad: () -> PadMapper?) :
    InputManager.InputDeviceListener {

    private val im = act.getSystemService(InputManager::class.java)

    private val census = object : Runnable {
        override fun run() {
            Thread { android.util.Log.i("FlashARM", "Maps: " + MapCensus.summary()) }.start()
            handler.postDelayed(this, 30_000)
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun onCreate() {
        handler.postDelayed(census, 5_000)
        MenuSfx.load(act)
        PauseMusic.load(act)
        OsdPrefs.load(act)
        GamePrefs.load(act)
        GamePrefs.apply(act)
        overlay.padConnected = gamepadAttached()
        im.registerInputDeviceListener(this, null)
    }

    fun onResume() {
        SleepWake.onResume()
        overlay.padConnected = gamepadAttached()
        PauseMusic.setForeground(true, act, overlay.menu)
    }

    fun onPause() {
        SleepWake.onPause(act)
        PauseMusic.setForeground(false, act, overlay.menu)
        pad()?.reset()
        if (!act.isFinishing && !overlay.menu) { overlay.editing = false; overlay.menu = true }
    }

    fun onDestroy() {
        handler.removeCallbacks(census)
        im.unregisterInputDeviceListener(this)
        PauseMusic.stop()
    }

    /** The quick menu opened or closed: sounds, music, and nothing left held in the game. */
    private var menuWasOpen = false

    fun onMenu(open: Boolean) {
        val wasOpen = menuWasOpen
        menuWasOpen = open
        if (open == wasOpen) return
        pad()?.reset()
        if (open) {
            MenuSfx.play(MenuSfx.Event.MENU_OPEN)
            PauseMusic.start(act)
        } else {
            if (wasOpen) MenuSfx.play(MenuSfx.Event.BACK)
            PauseMusic.stop()
        }
    }

    /** Every touch: the pad stops driving the menus, and auto-hide touch keys come back. */
    fun onTouch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            com.armsx.flashpoint.ui.PadInput.onTouch()
            overlay.touchTick++
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) { overlay.padConnected = gamepadAttached() }
    override fun onInputDeviceRemoved(deviceId: Int) { overlay.padConnected = gamepadAttached() }
    override fun onInputDeviceChanged(deviceId: Int) { overlay.padConnected = gamepadAttached() }
}

/** Game settings the pause menu's Options tab changes while a game runs; shared by every game. */
object GamePrefs {
    /** Screen orientation for games: an ActivityInfo constant by name. */
    // "device" follows the phone's own rotation setting, like ARMSX2; it comes first and is the default.
    val orientations = listOf(
        "device" to "Device", "sensorLandscape" to "Landscape", "landscape" to "Landscape (fixed)", "reverseLandscape" to "Upside down",
        "sensorPortrait" to "Portrait",
    )
    val orientation = androidx.compose.runtime.mutableStateOf("device")
    val volume = androidx.compose.runtime.mutableFloatStateOf(1f)
    private var prefs: android.content.SharedPreferences? = null

    fun load(ctx: android.content.Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs = p
        // "Rotate freely" was folded into Device.
        orientation.value = p.getString("game.orientation", "device")!!.let { if (it == "sensor") "device" else it }
        volume.floatValue = p.getFloat("game.volume", 1f)
    }

    /** Apply orientation and keep the screen on for [act]. */
    fun apply(act: Activity) {
        act.requestedOrientation = when (orientation.value) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "reverseLandscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "sensorPortrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "sensorLandscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // The screen always stays on while a game runs.
        act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun setOrientation(act: Activity, v: String) { orientation.value = v; prefs?.edit()?.putString("game.orientation", v)?.apply(); apply(act) }
    fun setVolume(v: Float) { volume.floatValue = v; prefs?.edit()?.putFloat("game.volume", v)?.apply() }
}
