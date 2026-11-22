package com.armsx.flashpoint.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The desktop launcher's Logo Sets: folders of platform logos (`<Platform>.png`) in the Flashpoint
 * folder's Data/LogoSets, copied from a desktop install. None picked: FlashARM's lettered badges.
 */
object LogoSets {
    val current = androidx.compose.runtime.mutableStateOf<java.io.File?>(null)

    fun dir(ctx: android.content.Context) = java.io.File((ctx.applicationContext as com.armsx.flashpoint.FpApp).library.root, "Data/LogoSets")

    fun list(ctx: android.content.Context): List<java.io.File> =
        dir(ctx).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }

    fun load(ctx: android.content.Context) {
        val n = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("logoSet", null)
        current.value = n?.let { java.io.File(dir(ctx), it) }?.takeIf { it.isDirectory }
    }

    fun set(ctx: android.content.Context, f: java.io.File?) {
        current.value = f
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("logoSet", f?.name).apply()
    }

    /** This platform's logo in the current set, matched like the launcher (any case, png or other image). */
    fun image(platform: String): java.io.File? {
        val set = current.value ?: return null
        val files = cache.getOrPut(set.path) { set.listFiles().orEmpty().associateBy { it.nameWithoutExtension.lowercase() } }
        return files[platform.lowercase()]
    }

    private val cache = HashMap<String, Map<String, java.io.File>>()
}

/** One launcher colour theme. */
data class FpPalette(
    val name: String,
    val header: Color, val sidebar: Color, val browse: Color, val card: Color, val panel: Color, val field: Color,
    val text: Color, val textDim: Color, val accent: Color,
    val play: Color = Color(0xFF5CB531), val playDark: Color = Color(0xFF3F8A1E),
    val light: Boolean = false,
)

/**
 * FlashARM's themes. The first is the desktop launcher's default dark look; the rest are FlashARM's
 * own palettes (the desktop's themes are stylesheets that ship with its install, so they cannot be
 * used here). Picked in Config > Theme.
 */
object FpThemes {
    private fun dark(name: String, base: Long, accent: Long, tint: Long = base) = FpPalette(
        name,
        header = Color(base).darken(0.55f), sidebar = Color(base).darken(0.38f), browse = Color(base),
        card = Color(base).darken(0.25f), panel = Color(base).darken(0.18f), field = Color(base).darken(0.48f),
        text = Color(0xFFE8E8EA), textDim = Color(tint).lighten(0.55f), accent = Color(accent),
    )

    val all: List<FpPalette> = listOf(
        FpPalette(
            "Flashpoint", header = Color(0xFF1C1C1E), sidebar = Color(0xFF262628), browse = Color(0xFF3A3A3C),
            card = Color(0xFF2C2C2E), panel = Color(0xFF2A2A2D), field = Color(0xFF1F1F21),
            text = Color(0xFFE6E6E8), textDim = Color(0xFFA0A0A6), accent = Color(0xFFF0A030),
        ),
        dark("Metal", 0xFF3B4147, 0xFF8FB3D9),
        dark("Bronze", 0xFF4A3A2C, 0xFFD9A066),
        dark("Steel Blue", 0xFF2F4357, 0xFF7FB2E5),
        dark("Forest", 0xFF2D4232, 0xFF9BD47A),
        dark("Gold", 0xFF4A4026, 0xFFF2C84B),
        dark("Gray", 0xFF4A4A4C, 0xFFD0D0D4),
        dark("Green", 0xFF2E4A2E, 0xFF6CDB6C),
        dark("Magenta", 0xFF4A2A47, 0xFFF06AD8),
        dark("Ocean", 0xFF22404F, 0xFF52D0E8),
        dark("Orange", 0xFF4D3424, 0xFFFF9A4A),
        dark("Pastel Pink", 0xFF4F3A45, 0xFFFFB3D4),
        dark("Purple", 0xFF372C4D, 0xFFB98CFF),
        dark("Red", 0xFF4A2626, 0xFFFF6A6A),
        dark("Red Velvet", 0xFF3F1D24, 0xFFE0566E),
        dark("Silver", 0xFF55585C, 0xFFE4E7EB),
        dark("Smoke", 0xFF333436, 0xFFB8BABE),
        dark("Teal", 0xFF1F4545, 0xFF4FD6C6),
        dark("Yellow", 0xFF4A4722, 0xFFFFE45C),
        FpPalette(
            "Light", header = Color(0xFFD9D9DE), sidebar = Color(0xFFE7E7EB), browse = Color(0xFFF4F4F6),
            card = Color(0xFFE4E4E8), panel = Color(0xFFEDEDF0), field = Color(0xFFFFFFFF),
            text = Color(0xFF1E1E22), textDim = Color(0xFF5E5E66), accent = Color(0xFFB86A00),
            play = Color(0xFF3F9A1C), playDark = Color(0xFF2F7A14), light = true,
        ),
    )

    fun byName(n: String?) = all.firstOrNull { it.name == n } ?: all.first()
}

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/** The launcher's colours, from the current theme (reads are tracked, so a theme change redraws). */
object Fp {
    val theme = androidx.compose.runtime.mutableStateOf(FpThemes.all.first())

    val Header get() = theme.value.header
    val Sidebar get() = theme.value.sidebar
    val Browse get() = theme.value.browse
    val Card get() = theme.value.card
    val CardSelected = Color(0xFFBDBDC2)
    val Panel get() = theme.value.panel
    val Field get() = theme.value.field
    val Text get() = theme.value.text
    val TextDim get() = theme.value.textDim
    val Play get() = theme.value.play
    val PlayDark get() = theme.value.playDark
    val Download = Color(0xFF3A7BD5)
    val Tag = Color(0xFF7FCB4E)
    val Accent get() = theme.value.accent
    /** Headings and other text that is white on dark themes. */
    val Strong get() = if (theme.value.light) Color(0xFF111114) else Color.White

    fun load(ctx: android.content.Context) {
        theme.value = FpThemes.byName(ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("theme", null))
    }

    fun set(ctx: android.content.Context, p: FpPalette) {
        theme.value = p
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("theme", p.name).apply()
    }

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
        colorScheme = if (Fp.theme.value.light) androidx.compose.material3.lightColorScheme(
            primary = Fp.Play, onPrimary = Color.White, secondary = Fp.Accent, background = Fp.Browse,
            surface = Fp.Panel, onSurface = Fp.Text, onBackground = Fp.Text, surfaceVariant = Fp.Field,
        ) else darkColorScheme(
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
