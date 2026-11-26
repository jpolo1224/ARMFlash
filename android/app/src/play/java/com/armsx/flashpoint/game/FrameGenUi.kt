package com.armsx.flashpoint.game

import androidx.compose.runtime.Composable

/*
 * The Play build has no frame generation: neither its library nor its settings and their words
 * (src/github/.../FrameGenUi.kt), and nothing that mentions it. These stand in for the GitHub
 * build's names, so the shared screens compile without knowing which build they are in.
 */

/** No frame generation here: Config leaves its section out. */
const val HAS_FRAME_GEN = false

/** The pause menu's frame generation card: not in this build. */
@Composable
fun FrameGenCard(host: OverlayHost) {
}

/** Config's frame generation section: not in this build (Config does not show it). */
@Composable
fun FrameGenConfig() {
}
