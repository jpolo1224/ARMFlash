package com.armsx.flashpoint.ui

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged

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

/**
 * `verticalScroll` for a launcher page, with [padScrollTop], and D-pad scrolling where a page
 * has nothing to land on: Up/Down first try to move focus; if there is no control that way
 * (a long stretch of text, the credits, the end of a page) or the next one is more than a
 * screen away, the page scrolls by most of a screen instead of getting stuck.
 */
@androidx.compose.runtime.Composable
fun Modifier.padVerticalScroll(kept: androidx.compose.foundation.ScrollState? = null): Modifier {
    // [kept]: a page's own state held elsewhere, so it is where it was when the page comes back.
    val state = kept ?: androidx.compose.foundation.rememberScrollState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(0) }
    return this
        .onSizeChanged { viewport = it.height }
        .onPreviewKeyEvent { e ->
            val down = e.key == Key.DirectionDown
            if (!(down || e.key == Key.DirectionUp) || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val before = state.value
            if (focus.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) {
                // Moved; if that jumped further than a screen, the page scrolled past unread content.
                scope.launch {
                    kotlinx.coroutines.delay(16)
                    val jumped = kotlin.math.abs(state.value - before)
                    if (viewport > 0 && jumped > viewport) state.animateScrollTo(before + (if (down) 1 else -1) * (viewport * 0.8f).toInt())
                }
                return@onPreviewKeyEvent true
            }
            if (!down && state.value == 0) return@onPreviewKeyEvent false
            scope.launch { state.animateScrollBy((if (down) 1 else -1) * viewport * 0.6f) }
            true
        }
        .then(padScrollTop(state))
        .then(Modifier.verticalScroll(state))
}

/**
 * A text box the controller can pass over: with a pad, landing on it only shows the selection
 * ring, A starts typing (focus and keyboard), and moving off ends it. Without that, focus moving
 * onto a text field opens the keyboard and holds the D-pad (the search bar, the driver variables
 * box). Touch taps straight into the field as usual. [field] gets the modifier for its text field.
 */
@androidx.compose.runtime.Composable
fun PadTextBox(modifier: Modifier = Modifier, field: @androidx.compose.runtime.Composable (Modifier) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val req = remember { androidx.compose.ui.focus.FocusRequester() }
    val pad = PadInput.active.value
    androidx.compose.runtime.LaunchedEffect(typing) { if (typing) runCatching { req.requestFocus() } }
    androidx.compose.foundation.layout.Box(
        modifier.padFocus()
            .then(if (pad && !typing) Modifier.clickable { typing = true } else Modifier),
    ) {
        field(
            Modifier.focusRequester(req)
                .focusProperties { canFocus = !pad || typing }
                .onFocusChanged {
                    if (it.isFocused) fieldFocused = true
                    else if (fieldFocused) { fieldFocused = false; typing = false }
                },
        )
    }
}

/**
 * A slider under the pad: Material's Slider takes D-pad up and down as "change the value", so
 * the highlight could never leave it that way. Up and down move between controls; left and
 * right still change the value.
 */
fun Modifier.sliderPadKeys(): Modifier = composed {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    onPreviewKeyEvent { e ->
        val dir = when (e.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return@onPreviewKeyEvent false
        }
        if (e.type == KeyEventType.KeyDown) fm.moveFocus(dir)
        true
    }
}
