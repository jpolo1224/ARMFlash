package com.armsx.flashpoint.game

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.ui.Fp

/*
 * The Play build has no frame generation: neither its library nor its settings and their words
 * (src/github/.../FrameGenUi.kt). These stand in for the GitHub build's composables of the same
 * names, so the shared screens call them without knowing which build they are in.
 */

/** The pause menu's frame generation card: not in this build. */
@Composable
fun FrameGenCard(host: OverlayHost) {
}

/** Config's frame generation section: a note on where to find it. */
@Composable
fun FrameGenConfig() {
    Text("Frame generation is available in the GitHub version of ARMFlash.", color = Fp.TextDim, fontSize = 13.sp)
}
