package com.armsx.flashpoint.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * A slider with a step arrow at each end of its track, held to repeat (landing the thumb on an
 * exact value is fiddly on a small screen), and, given [valueText] and [onTyped], its value at the
 * end of the row, which can be tapped to type one. Neither the arrows nor the value are a stop for
 * the pad: left and right on the slider already change it.
 *
 * [step] is what an arrow moves; a slider with fixed stops snaps to them. [onValueChangeFinished]
 * runs when an arrow is let go, as it does when the thumb is.
 */
@Composable
fun ArrowSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueText: String? = null,
    onTyped: ((Double) -> Unit)? = null,
) {
    val latestValue by rememberUpdatedState(value)
    fun stepped(delta: Int): Float {
        val raw = latestValue + delta * step
        val snapped = if (step > 0f) Math.round((raw - valueRange.start) / step) * step + valueRange.start else raw
        return snapped.coerceIn(valueRange.start, valueRange.endInclusive)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SliderStepArrow("‹", "Decrease", enabled && value > valueRange.start, onRelease = onValueChangeFinished) {
            onValueChange(stepped(-1))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            modifier = Modifier.weight(1f).then(sliderModifier),
        )
        SliderStepArrow("›", "Increase", enabled && value < valueRange.endInclusive, onRelease = onValueChangeFinished) {
            onValueChange(stepped(1))
        }
        if (valueText != null) {
            TypeableValue(valueText, enabled) { typed ->
                firstNumberIn(typed)?.let { onTyped?.invoke(it) }
                onValueChangeFinished?.invoke()
            }
        }
    }
}

/** One step down or up, held to repeat; [onRelease] when let go. */
@Composable
fun SliderStepArrow(glyph: String, description: String, enabled: Boolean, onRelease: (() -> Unit)? = null, onStep: () -> Unit) {
    val latestOnStep by rememberUpdatedState(onStep)
    val latestOnRelease by rememberUpdatedState(onRelease)
    val latestEnabled by rememberUpdatedState(enabled)
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    if (!latestEnabled) return@awaitEachGesture
                    latestOnStep()
                    // A tap is one step; held past the first pause, it steps until released.
                    var wait = 420L
                    while (true) {
                        val released = withTimeoutOrNull(wait) { waitForUpOrCancellation(); true } ?: false
                        if (released) break
                        latestOnStep()
                        wait = 70L
                    }
                    latestOnRelease?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) color else color.copy(alpha = 0.32f), fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    }
}

/** A slider's value, which can be tapped to type one; [onSubmit] gets the text as typed. */
@Composable
fun TypeableValue(text: String, enabled: Boolean = true, onSubmit: (String) -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Text(
            text,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .focusProperties { canFocus = false }
                .clickable(enabled = enabled) { editing = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    } else {
        val start = remember { firstNumberIn(text)?.let(::plainNumber).orEmpty() }
        var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var hadFocus by remember { mutableStateOf(false) }
        val finish = {
            if (editing) {
                editing = false
                keyboard?.hide()
                onSubmit(field.text)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish() }),
            textStyle = TextStyle(color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
            cursorBrush = SolidColor(color),
            modifier = Modifier
                .width(84.dp)
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() }
                .border(1.5.dp, color, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The first number in a label ("85%" is 85, "0.50" is 0.5), or null for one with none. A decimal
 *  comma counts, since labels may be formatted in the user's locale. */
fun firstNumberIn(text: String): Double? =
    Regex("-?\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull()

private fun plainNumber(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** A sideways swipe moves to the next or previous tab: [onSwipe] gets 1 for a swipe to the left
 *  (the next tab) and -1 for one to the right. A slider or a row that scrolls sideways takes the
 *  drag first and keeps it, and a vertical drag is left to the page's scroll. Once a swipe is ours
 *  it is consumed, which also stops the row it started on from taking it as a tap. */
fun Modifier.swipeBetweenTabs(onSwipe: (Int) -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onSwipe)
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val distance = 72.dp.toPx()
        awaitEachGesture {
            val pointer = awaitFirstDown(requireUnconsumed = false).id
            var dx = 0f
            var dy = 0f
            var mode = 0 // 0 undecided, 1 a swipe between tabs, 2 someone else's
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == pointer } ?: break
                if (!change.pressed) break
                if (mode == 0 && change.isConsumed) mode = 2
                val d = change.positionChange()
                dx += d.x
                dy += d.y
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) mode = if (abs(dx) > abs(dy) * 1.5f) 1 else 2
                if (mode == 1) change.consume()
            }
            if (mode == 1 && abs(dx) >= distance) latest(if (dx < 0) 1 else -1)
        }
    }
}
