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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The mouse cursor drawn over a game when a controller, the touchpad or point & click drives
 * the mouse. Shared by every game, like the other game settings.
 *
 * Modern Mac is Capitaine Cursors' arrow (Keefer Rourke and others, LGPL-3.0, drawn from its SVG
 * paths); Linux is DMZ-White's arrow (Novell, CC BY-SA 3.0, assets/cursors/dmz_white.png). The
 * other styles are ARMFlash's own drawings in the manner of each era's pointer.
 */
object Cursors {
    val styles = listOf(
        "armflash" to "ARMFlash",
        "modernWin" to "Modern Windows",
        "modernMac" to "Modern Mac",
        "linux" to "Linux",
        "classicMac" to "Classic Mac",
        "classicX" to "Classic X11",
    )
    val sizes = listOf(0.8f to "Small", 1f to "Medium", 1.4f to "Large", 1.9f to "Huge")

    val style = mutableStateOf("armflash")
    val scale = mutableFloatStateOf(1f)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var dmz: androidx.compose.ui.graphics.ImageBitmap? = null

    // Capitaine Cursors (LGPL-3.0), src/svg/dark/default.svg: shadow, white outline, dark arrow.
    private val CAPITAINE_OUTLINE = "m3.2341734 2.0073307v12.1416143c.000038.397319.3615318.758813.7588509.758851 0 0 2.9462994-.955727 2.8277289-.922262l1.9388034 2.961674c.223603.224058.5944686.284197.8774214.142284l.765271-.363929c.248857-.120991.420092-.387363.426853-.663995l-.663903-3.53346 2.720609-1.272282c.277345-.280532.277345-.786602 0-1.067134l-8.3473596-8.7267854c-.6222501-.62225019-1.3170237-.178034-1.304275.5454241z"
    private val CAPITAINE_ARROW = "m3.9930243 14.139217v-12.1416148l8.3473597 8.7267858-2.934036 1.485708.692224 3.866308-.7935913.339365-2.1759443-3.198814z"
    private val capOutline by lazy { androidx.compose.ui.graphics.vector.PathParser().parsePathString(CAPITAINE_OUTLINE).toPath() }
    private val capArrow by lazy { androidx.compose.ui.graphics.vector.PathParser().parsePathString(CAPITAINE_ARROW).toPath() }

    fun load(ctx: Context) {
        if (dmz == null) dmz = runCatching {
            ctx.assets.open("cursors/dmz_white.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap()
        style.value = prefs(ctx).getString("cursor.style", "armflash")!!
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

    // 1-bit classic pointer, 11 x 17: '#' black, '.' white, ' ' clear.
    private val PIXELS = listOf(
        "#",
        "##",
        "#.#",
        "#..#",
        "#...#",
        "#....#",
        "#.....#",
        "#......#",
        "#.......#",
        "#........#",
        "#.....#####",
        "#..#..#",
        "#.# #..#",
        "##  #..#",
        "#    #..#",
        "     #..#",
        "      ##",
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
                    // Capitaine's 24-unit arrow, its tip (3.99, 2.0) on the hot spot.
                    val k = 1.6f * s
                    withTransform({ scale(k, k, Offset.Zero) }) {
                        translate(-3.993f, -1.998f) {
                            translate(0.25f, 0.6f) { drawPath(capOutline, Color.Black.copy(alpha = 0.22f)) }
                            drawPath(capOutline, Color.White)
                            drawPath(capArrow, Color(0xFF1A1A1A))
                        }
                    }
                }
                "linux" -> dmz?.let { img ->
                    // DMZ-White, 48 px with its hot spot at (14, 8).
                    val k = 0.55f * s
                    drawImage(
                        img, dstOffset = androidx.compose.ui.unit.IntOffset((-14 * k).toInt(), (-8 * k).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize((48 * k).toInt(), (48 * k).toInt()),
                    )
                } ?: run {
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
