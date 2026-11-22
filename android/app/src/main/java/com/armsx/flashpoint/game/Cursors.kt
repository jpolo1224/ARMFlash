package com.armsx.flashpoint.game

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

/**
 * The mouse cursor drawn over a game when a controller, the touchpad or point & click drives
 * the mouse. Every style is drawn here from FlashARM's own shapes, in the manner of each era's
 * pointer (no system artwork is copied). Shared by every game, like the other game settings.
 */
object Cursors {
    val styles = listOf(
        "flasharm" to "FlashARM",
        "modernWin" to "Modern Windows",
        "modernMac" to "Modern Mac",
        "linux" to "Linux",
        "classicMac" to "Classic Mac",
        "classicX" to "Classic X11",
    )
    val sizes = listOf(0.8f to "Small", 1f to "Medium", 1.4f to "Large", 1.9f to "Huge")

    val style = mutableStateOf("flasharm")
    val scale = mutableFloatStateOf(1f)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        style.value = prefs(ctx).getString("cursor.style", "flasharm")!!
        scale.floatValue = prefs(ctx).getFloat("cursor.size", 1f)
    }

    fun setStyle(ctx: Context, v: String) { style.value = v; prefs(ctx).edit().putString("cursor.style", v).apply() }
    fun setSize(ctx: Context, v: Float) { scale.floatValue = v; prefs(ctx).edit().putFloat("cursor.size", v).apply() }

    /** The classic arrow outline, tip at (0,0), in units of [u]. */
    private fun arrow(u: Float, tail: Boolean = true) = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f * u); lineTo(4f * u, 13f * u)
        if (tail) { lineTo(7f * u, 20f * u); lineTo(10f * u, 19f * u); lineTo(7f * u, 12f * u) }
        lineTo(12f * u, 12f * u); close()
    }

    // 1-bit classic pointer, 11 x 16: '#' black, '.' white, ' ' clear.
    private val PIXELS = listOf(
        "#          ",
        "##         ",
        "#.#        ",
        "#..#       ",
        "#...#      ",
        "#....#     ",
        "#.....#    ",
        "#......#   ",
        "#.......#  ",
        "#....#####",
        "#..#..#    ",
        "#.# #..#   ",
        "##  #..#   ",
        "#    #..#  ",
        "     #..#  ",
        "      ##   ",
    )

    /** Draw the current cursor with its hot spot at [at]. */
    fun DrawScope.drawCursor(at: Offset) {
        val s = scale.floatValue * density
        translate(at.x, at.y) {
            when (style.value) {
                "modernWin" -> {
                    val p = arrow(1.35f * s)
                    translate(1.2f * s, 1.6f * s) { drawPath(p, Color.Black.copy(alpha = 0.28f)) }
                    drawPath(p, Color.White)
                    drawPath(p, Color.Black, style = Stroke(width = 1.3f * s, join = StrokeJoin.Miter))
                }
                "modernMac" -> {
                    val p = arrow(1.4f * s)
                    translate(0.8f * s, 2.2f * s) { drawPath(p, Color.Black.copy(alpha = 0.3f)) }
                    drawPath(p, Color.White, style = Stroke(width = 3.2f * s, join = StrokeJoin.Round))
                    drawPath(p, Color.Black)
                }
                "linux" -> {
                    val p = arrow(1.3f * s, tail = false)
                    drawPath(p, Color.White, style = Stroke(width = 2.6f * s, join = StrokeJoin.Round))
                    drawPath(p, Color(0xFF2E3436))
                    // A short rounded tail, set apart from the head.
                    val t = Path().apply {
                        moveTo(5.2f * 1.3f * s, 13f * 1.3f * s); lineTo(8.2f * 1.3f * s, 19.5f * 1.3f * s)
                    }
                    drawPath(t, Color.White, style = Stroke(width = 5.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(t, Color(0xFF2E3436), style = Stroke(width = 2.8f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                "classicMac" -> {
                    val px = 1.6f * s
                    PIXELS.forEachIndexed { y, row ->
                        row.forEachIndexed { x, c ->
                            val col = when (c) { '#' -> Color.Black; '.' -> Color.White; else -> null }
                            if (col != null) drawRect(col, Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
                        }
                    }
                }
                "classicX" -> {
                    // The old X pointer: an X centred on the hot spot.
                    val r = 8f * s
                    val x = Path().apply { moveTo(-r, -r); lineTo(r, r); moveTo(r, -r); lineTo(-r, r) }
                    drawPath(x, Color.White, style = Stroke(width = 5.5f * s))
                    drawPath(x, Color.Black, style = Stroke(width = 3f * s))
                }
                else -> {
                    val p = arrow(1.5f * s)
                    drawPath(p, Color.White)
                    drawPath(p, Color.Black, style = Stroke(width = 1.6f * s))
                }
            }
        }
    }
}
