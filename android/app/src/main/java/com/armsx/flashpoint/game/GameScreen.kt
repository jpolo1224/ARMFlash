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
            Thread { android.util.Log.i("ARMFlash", "Maps: " + MapCensus.summary()) }.start()
            handler.postDelayed(this, 30_000)
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** This run's playtime, saved as it goes (see [PlaytimeLedger]). */
    private val playtime = PlaytimeLedger.Session(act, act.intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty())
    private val playtimeTick = object : Runnable {
        override fun run() {
            playtime.write(final = false)
            handler.postDelayed(this, 30_000)
        }
    }

    fun onCreate() {
        // Which phone a game's log came from (testers send these).
        Thread { android.util.Log.i("ARMFlash", DeviceInfo.line(act)) }.start()
        handler.postDelayed(census, 5_000)
        handler.postDelayed(playtimeTick, 30_000)
        MenuSfx.load(act)
        PauseMusic.load(act)
        OsdPrefs.load(act)
        GamePrefs.load(act)
        TouchPrefs.load(act)
        Cursors.load(act)
        GamePrefs.apply(act)
        overlay.padConnected = gamepadAttached()
        im.registerInputDeviceListener(this, null)
        PadRouter.load(act)
    }

    fun onResume() {
        SleepWake.onResume()
        overlay.padConnected = gamepadAttached()
        PauseMusic.setForeground(true, act, overlay.menu)
        // Discord friends for the quick menu's Friends tab and the "is now online" banner.
        com.armsx.flashpoint.discord.DiscordPresence.watchFriends()
    }

    fun onPause() {
        // Final when the game is closing: the launcher adds it as soon as it is back.
        playtime.write(final = act.isFinishing)
        SleepWake.onPause(act)
        PauseMusic.setForeground(false, act, overlay.menu)
        com.armsx.flashpoint.discord.DiscordPresence.stopWatchingFriends()
        pad()?.reset()
        gestures.reset()
        if (!act.isFinishing && !overlay.menu && !overlay.holdMenu) { overlay.editing = false; overlay.menu = true }
    }

    fun onDestroy() {
        playtime.write(final = true)
        handler.removeCallbacks(playtimeTick)
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
        gestures.reset()
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
        gestures.onTouch(ev, act.resources.displayMetrics.density)
    }

    /** ARMSX2's Gesture Control: swipes and a double-tap on the game press keys. */
    private val gestures = Gestures(overlay)

    override fun onInputDeviceAdded(deviceId: Int) { overlay.padConnected = gamepadAttached(); PadRouter.devicesChanged() }
    override fun onInputDeviceRemoved(deviceId: Int) { overlay.padConnected = gamepadAttached(); PadRouter.forgetDevice(deviceId) }
    override fun onInputDeviceChanged(deviceId: Int) { overlay.padConnected = gamepadAttached(); PadRouter.devicesChanged() }
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
