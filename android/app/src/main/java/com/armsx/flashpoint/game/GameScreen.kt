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

    fun onCreate() {
        MenuSfx.load(act)
        PauseMusic.load(act)
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
