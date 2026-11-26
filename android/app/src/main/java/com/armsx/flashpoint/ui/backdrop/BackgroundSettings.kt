package com.armsx.flashpoint.ui.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.ui.Chip
import com.armsx.flashpoint.ui.ConfigRow
import com.armsx.flashpoint.ui.Fp
import com.armsx.flashpoint.ui.padFocus
import com.armsx.flashpoint.ui.sliderPadKeys

/**
 * Config's Background section after ARMSX2's library background settings (ui/settings/AppTab.kt,
 * ARMSX2 team): the wave on or off, the 2D wave, its colour (quick picks, custom RGB, RGB cycle),
 * the top bar's colour and the library's opacity over the background. The picture of your own is
 * the Custom Background row Config puts between [BackgroundChoice] and [BackgroundSettings].
 */
@Composable
fun BackgroundChoice() {
    ConfigRow(
        "Library Background",
        "The PS3 XMB wave behind the library, as in ARMSX2. A picture of your own (below) takes its place. Off keeps the theme's colours.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Off", !LibraryBackground.wave.value) { LibraryBackground.setWave(false) }
            Chip("XMB Wave", LibraryBackground.wave.value) { LibraryBackground.setWave(true) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundSettings() {
    ConfigRow(
        "Simple animated background",
        "Use the lightweight 2D animated wave background instead of the 3D GL one. It's the same backdrop devices that can't run the 3D wave already use. Lighter on the GPU, and recolorable with the options below. No effect if you've set a custom background.",
    ) { Switch(LibraryBackground.animated2D.value, { LibraryBackground.setAnimated2D(it) }) }

    // Library background (wave) colour. Separate from the theme, which colours the UI chrome; this
    // recolours the animated backdrop itself, with the white waves riding over it. Unset = the
    // built-in blue. Live: XmbGlView's GL thread reads the new colour on its next frame.
    Heading(
        "Background Color",
        "Recolor the animated library background. The white waves ride over your color; the theme stays separate. Updates live as you drag. Applies to both the 3D GL wave and the simpler 2D wave.",
    )
    ConfigRow(
        "RGB Cycle",
        "Continuously drift the background through the colour spectrum, like RGB peripherals. Overrides the fixed colour below.",
    ) { Switch(LibraryBackgroundColorPreferences.rgbCycle.value, { LibraryBackgroundColorPreferences.setRgbCycle(it) }) }
    if (!LibraryBackgroundColorPreferences.rgbCycle.value) {
        val customized = LibraryBackgroundColorPreferences.color.value != 0
        val argb = if (customized) LibraryBackgroundColorPreferences.color.value else LibraryBackgroundColorPreferences.DefaultDisplayColor
        val r = android.graphics.Color.red(argb)
        val g = android.graphics.Color.green(argb)
        val b = android.graphics.Color.blue(argb)
        // Custom-slider mode: open when a non-preset colour is active, or the player picks "Custom".
        val customOpen = remember {
            mutableStateOf(customized && LibraryBackgroundColorPreferences.PRESETS.none { it == argb })
        }
        // Quick picks (XMB palette) and a "Custom" chip that shows the RGB sliders; the active
        // swatch (or Custom) is ringed.
        FlowRow(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LibraryBackgroundColorPreferences.PRESETS.forEach { preset ->
                Swatch(preset, selected = !customOpen.value && preset == argb) {
                    customOpen.value = false; LibraryBackgroundColorPreferences.set(preset)
                }
            }
            Chip("Custom", customOpen.value) {
                customOpen.value = true
                if (!customized) LibraryBackgroundColorPreferences.set(LibraryBackgroundColorPreferences.DefaultDisplayColor)
            }
        }
        // Preview and RGB sliders only in Custom mode.
        if (customOpen.value) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 66.dp, height = 34.dp).clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Fp.TextDim, RoundedCornerShape(9.dp))
                        .background(Brush.verticalGradient(listOf(Color(r * 0.20f / 255f, g * 0.20f / 255f, b * 0.20f / 255f, 1f), Color(argb)))),
                )
                Spacer(Modifier.width(10.dp))
                Text(String.format("#%06X", 0xFFFFFF and argb), color = Fp.TextDim, fontSize = 14.sp)
            }
            listOf(Triple("Red", 16, r), Triple("Green", 8, g), Triple("Blue", 0, b)).forEach { (label, shift, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$label $value", color = Fp.Text, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { v ->
                            val cleared = argb and (0xFF shl shift).inv()
                            LibraryBackgroundColorPreferences.set(cleared or (v.toInt().coerceIn(0, 255) shl shift) or (0xFF shl 24))
                        },
                        valueRange = 0f..255f,
                        modifier = Modifier.widthIn(max = 360.dp).sliderPadKeys(),
                    )
                }
            }
        }
        if (customized) {
            Row(Modifier.padding(top = 4.dp)) {
                Chip("Reset", false) { customOpen.value = false; LibraryBackgroundColorPreferences.reset() }
            }
        }
    }

    // Colour of the top bar itself, as opposed to the animated backdrop above. "Default" hands it
    // back to the theme.
    Heading(
        "Library Bar Color",
        "Colour the top bar itself. Default follows your theme. This is the bar; the colours above recolour the animated background behind the library.",
    )
    val barArgb = LibraryChromePreferences.barColor.value
    FlowRow(
        Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Chip("Default", barArgb == 0) { LibraryChromePreferences.setBarColor(0) }
        LibraryChromePreferences.BAR_PRESETS.forEach { preset ->
            Swatch(preset, selected = barArgb == preset) { LibraryChromePreferences.setBarColor(preset) }
        }
    }

    // How much of the background shows through the bars, panels and game tiles.
    val opacity = LibraryChromePreferences.libraryOpacity.value
    Heading(
        "Library opacity: $opacity%",
        "How solid the bars, panels and game tiles are over a background. 100% hides it behind them; lower lets it show through.",
    )
    Slider(
        value = opacity.toFloat(),
        onValueChange = { LibraryChromePreferences.setLibraryOpacity(it.toInt()) },
        valueRange = 20f..100f,
        modifier = Modifier.widthIn(max = 420.dp).sliderPadKeys(),
    )
}

@Composable
private fun Heading(title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
        Text(title, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(desc, color = Fp.TextDim, fontSize = 13.sp)
    }
}

/** A colour swatch: a rounded square, ringed when it is the one in use. */
@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.size(34.dp).padFocus(shape).clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Fp.Play else Fp.TextDim, shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().padding(if (selected) 3.dp else 1.dp).clip(RoundedCornerShape(7.dp)).background(Color(argb)))
    }
}
