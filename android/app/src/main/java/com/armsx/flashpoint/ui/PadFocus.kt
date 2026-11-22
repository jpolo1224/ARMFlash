package com.armsx.flashpoint.ui

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Whether a controller (or the keyboard's arrows) is driving the launcher right now, as in
 * ARMSX2's PadInput: a pad or arrow key turns it on, a touch turns it off. The selection ring
 * only shows while it is on, so touch users never see a selection they did not make.
 */
object PadInput {
    val active = mutableStateOf(false)

    fun onKey(e: KeyEvent) {
        val pad = e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            e.isFromSource(InputDevice.SOURCE_DPAD)
        val nav = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB -> true
            else -> false
        }
        if (pad || nav) active.value = true
    }

    fun onTouch() {
        active.value = false
    }
}

private val focusRing = Fp.Accent

/**
 * ARMSX2's controller selection ring (SettingsWidgets.controllerFocusable): a solid inner border
 * with a translucent outer one, drawn with borders rather than a tinted shadow because GPU
 * drivers render those as black boxes. Shows when this element, or a control inside it, has
 * focus while the pad is active. Put it before `clickable` so the ring follows that focus.
 */
fun Modifier.padFocus(shape: Shape = RoundedCornerShape(6.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .then(
            if (focused && PadInput.active.value) {
                Modifier.border(3.dp, focusRing.copy(alpha = 0.30f), shape).border(1.5.dp, focusRing, shape)
            } else {
                Modifier
            },
        )
}

/** [padFocus] for callers outside this package. */
fun padFocusModifier(shape: Shape): Modifier = Modifier.padFocus(shape)

/**
 * For a scrolling page under a fixed bar (the launcher's tabs): when the pad moves focus up out
 * of the page, scroll it back to the top. Without this, a page whose top has nothing to focus
 * (a title, a logo) stays scrolled down, since focus never lands there to pull it into view.
 */
fun Modifier.padScrollTop(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    var had by remember { mutableStateOf(false) }
    onFocusChanged {
        if (had && !it.hasFocus && PadInput.active.value) scope.launch { state.animateScrollTo(0) }
        had = it.hasFocus
    }
}

/** `verticalScroll` for a launcher page, with [padScrollTop]. */
@androidx.compose.runtime.Composable
fun Modifier.padVerticalScroll(): Modifier {
    val state = androidx.compose.foundation.rememberScrollState()
    return padScrollTop(state).then(Modifier.verticalScroll(state))
}
