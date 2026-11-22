package com.armsx.flashpoint.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The desktop launcher's default dark look. */
object Fp {
    val Header = Color(0xFF1C1C1E)
    val Sidebar = Color(0xFF262628)
    val Browse = Color(0xFF3A3A3C)
    val Card = Color(0xFF2C2C2E)
    val CardSelected = Color(0xFFBDBDC2)
    val Panel = Color(0xFF2A2A2D)
    val Field = Color(0xFF1F1F21)
    val Text = Color(0xFFE6E6E8)
    val TextDim = Color(0xFFA0A0A6)
    val Play = Color(0xFF5CB531)
    val PlayDark = Color(0xFF3F8A1E)
    val Download = Color(0xFF3A7BD5)
    val Tag = Color(0xFF7FCB4E)
    val Accent = Color(0xFFF0A030)

    fun platformColor(p: String): Color = when (p.lowercase()) {
        "flash" -> Color(0xFFE0262B)
        "html5" -> Color(0xFFE5612A)
        "shockwave" -> Color(0xFFB03A8F)
        "java" -> Color(0xFF3B6FB6)
        "unity" -> Color(0xFF555555)
        "silverlight" -> Color(0xFF6A7FA0)
        else -> Color(0xFF707070)
    }

    fun platformGlyph(p: String): String = when (p.lowercase()) {
        "flash" -> "F"
        "html5" -> "5"
        "shockwave" -> "S"
        "java" -> "J"
        "unity" -> "U"
        "silverlight" -> "SL"
        else -> p.take(1).uppercase()
    }
}

@Composable
fun FpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fp.Play,
            onPrimary = Color.White,
            secondary = Fp.Accent,
            background = Fp.Browse,
            surface = Fp.Panel,
            onSurface = Fp.Text,
            onBackground = Fp.Text,
            surfaceVariant = Fp.Field,
        ),
        content = content,
    )
}
