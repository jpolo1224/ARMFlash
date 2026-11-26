package com.armsx.flashpoint.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The desktop launcher's Logo Sets: folders of platform logos (`<Platform>.png`) in the Flashpoint
 * folder's Data/LogoSets. None picked: Flashpoint's default platform logos (Data/Logos), as the
 * desktop launcher shows them; a platform with no logo keeps ARMFlash's lettered badge.
 */
object LogoSets {
    val current = androidx.compose.runtime.mutableStateOf<java.io.File?>(null)

    fun dir(ctx: android.content.Context) = java.io.File((ctx.applicationContext as com.armsx.flashpoint.FpApp).library.root, "Data/LogoSets")

    fun list(ctx: android.content.Context): List<java.io.File> =
        dir(ctx).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }

    private var defaults: java.io.File? = null

    fun load(ctx: android.content.Context) {
        defaults = java.io.File((ctx.applicationContext as com.armsx.flashpoint.FpApp).library.root, "Data/Logos").takeIf { it.isDirectory }
        val n = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("logoSet", null)
        current.value = n?.let { java.io.File(dir(ctx), it) }?.takeIf { it.isDirectory }
    }

    fun set(ctx: android.content.Context, f: java.io.File?) {
        current.value = f
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("logoSet", f?.name).apply()
    }

    /** This platform's logo in the current set, matched like the launcher (any case, png or other image). */
    fun image(platform: String): java.io.File? {
        val set = current.value ?: defaults ?: return null
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
 * ARMFlash's themes. The first is the desktop launcher's default dark look; the rest are ARMFlash's
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

    /** The built-in palettes, replaced by the desktop themes' own colors once they are read. */
    var all: List<FpPalette> = builtIn()
        private set

    /**
     * Desktop Flashpoint's themes from the Flashpoint folder's Data/Themes, each read from its
     * CSS ([ThemeCss]); ARMFlash's own "Flashpoint" default stays first. A theme whose colors
     * can't be read keeps the built-in palette of the same name, if there is one.
     */
    fun loadDesktop(root: java.io.File) {
        val builtIn = builtIn()
        val read = java.io.File(root, "Data/Themes").listFiles { f -> f.isDirectory }.orEmpty()
            .filter { it.name != "Colorized" }
            .mapNotNull { dir -> runCatching { fromCss(dir) }.getOrNull() ?: builtIn.firstOrNull { it.name.replace(" ", "") == dir.name.replace(" ", "") } }
            .sortedBy { it.name.lowercase() }
        if (read.isNotEmpty()) all = listOf(builtIn.first()) + read
    }

    private fun fromCss(dir: java.io.File): FpPalette? {
        val p = ThemeCss.read(dir) ?: return null
        fun c(vararg names: String) = names.firstNotNullOfOrNull { ThemeCss.color(p, it) }
        val header = c("--layout__primary-background") ?: return null
        val browse = c("--layout__secondary-background") ?: header
        val sidebar = c("--layout__tertiary-background") ?: browse
        val card = c("--layout__quaternary-background") ?: sidebar
        val light = (browse.red * 0.299f + browse.green * 0.587f + browse.blue * 0.114f) > 0.5f
        // Themes that only set backgrounds (Metal, FlatMetal) keep the launcher's default text.
        val text = c("--layout__primary-text-color") ?: if (light) Color(0xFF111114) else Color(0xFFE6E6E8)
        val dim = c("--layout__secondary-text-color")?.takeIf { it != text } ?: text.copy(alpha = 0.7f)
        // The theme's accent, unless it would vanish against the background.
        fun lum(x: Color) = x.red * 0.299f + x.green * 0.587f + x.blue * 0.114f
        val accent = listOf("--highlight", "--layout__primary-outline-color", "--layout__scroll-thumb-color", "--layout__slider-background")
            .mapNotNull { ThemeCss.color(p, it) }.firstOrNull { kotlin.math.abs(lum(it) - lum(browse)) > 0.22f }
            ?: if (light) Color(0xFFB86A00) else Color(0xFFF0A030)
        return FpPalette(
            p.name, header = header, sidebar = sidebar, browse = browse, card = card, panel = sidebar,
            field = if (light) header.darken(0.06f) else header.darken(0.25f),
            text = text, textDim = dim, accent = accent, light = light,
            play = if (light) Color(0xFF3F9A1C) else Color(0xFF5CB531), playDark = if (light) Color(0xFF2F7A14) else Color(0xFF3F8A1E),
        )
    }

    private fun builtIn(): List<FpPalette> = listOf(
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

    // With a background behind the library (ui/backdrop), pages let it show and bars, panels
    // and tiles take the library opacity; the top bar can have a colour of its own.
    private val behind get() = com.armsx.flashpoint.ui.backdrop.LibraryBackground.active
    private fun Color.overBackground() =
        if (behind) copy(alpha = alpha * com.armsx.flashpoint.ui.backdrop.LibraryChromePreferences.libraryOpacity.value / 100f) else this

    val Header get() = (com.armsx.flashpoint.ui.backdrop.LibraryChromePreferences.barColor.value.takeIf { it != 0 }?.let { Color(it) }
        ?: theme.value.header).overBackground()
    val Sidebar get() = theme.value.sidebar.overBackground()
    val Browse get() = if (behind) Color.Transparent else theme.value.browse
    /** Under the whole launcher: the theme's header colour, or nothing when a background shows. */
    val Base get() = if (behind) Color.Transparent else theme.value.header
    val Card get() = theme.value.card.overBackground()
    val CardSelected = Color(0xFFBDBDC2)
    val Panel get() = theme.value.panel.overBackground()
    /** Panels of dialogs, which stay opaque over a background. */
    val PanelSolid get() = theme.value.panel
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
            primary = Fp.Play, onPrimary = Color.White, secondary = Fp.Accent, background = Fp.theme.value.browse,
            surface = Fp.PanelSolid, onSurface = Fp.Text, onBackground = Fp.Text, surfaceVariant = Fp.Field,
        ) else darkColorScheme(
            primary = Fp.Play,
            onPrimary = Color.White,
            secondary = Fp.Accent,
            background = Fp.theme.value.browse,
            surface = Fp.PanelSolid,
            onSurface = Fp.Text,
            onBackground = Fp.Text,
            surfaceVariant = Fp.Field,
        ),
        content = content,
    )
}
