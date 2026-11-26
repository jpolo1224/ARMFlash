package com.armsx.flashpoint.ui.backdrop

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.armsx.flashpoint.data.CustomMedia
import com.armsx.flashpoint.ui.Fp

/**
 * The library's background, after ARMSX2's (ui/home/LibraryBackground.kt and its HomeScreen
 * backdrop, ARMSX2 team): the PS3 XMB wave ([XmbGlView]) or its 2D Canvas version
 * ([LibraryWaveBackground]), or a picture of the player's own, with the wave's colour options.
 * The wave is on by default, in ARMFlash's own colour (#454859); off ([wave] false and no
 * picture) keeps the Flashpoint theme's own colours.
 *
 * A picture is a [CustomMedia] file (copied into app storage), and it overrides the wave, as in
 * ARMSX2. The screensaver backgrounds ARMSX2 offers next to the wave are not part of this.
 *
 * Settings live in the "settings" preferences of the launcher process; [load] runs on the main
 * thread before any of this is drawn (its Compose state must be made there).
 */
object LibraryBackground {
    private const val PREF_WAVE = "library.background.wave"
    private const val PREF_ANIM = "library.background.animated2d"

    /** The XMB wave behind the library (when no picture is set). On unless turned off. */
    val wave = mutableStateOf(true)

    /**
     * The lightweight 2D wave in place of the GLES3 one (ARMSX2's "Simple animated background").
     * Devices where the GL wave cannot start get the 2D one anyway.
     */
    val animated2D = mutableStateOf(false)

    /** A picture of the player's own is set; [pictureVersion] changes when it is replaced. */
    val hasPicture = mutableStateOf(false)
    val pictureVersion = mutableIntStateOf(0)

    /** Something is drawn behind the library, so its page background steps aside. */
    val active: Boolean get() = wave.value || hasPicture.value

    private var prefs: SharedPreferences? = null
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).also { prefs = it }
        wave.value = p.getBoolean(PREF_WAVE, true)
        animated2D.value = p.getBoolean(PREF_ANIM, false)
        hasPicture.value = CustomMedia.has(ctx, CustomMedia.Kind.BACKGROUND)
        LibraryBackgroundColorPreferences.load(p)
        LibraryChromePreferences.load(p)
    }

    fun setWave(on: Boolean) {
        wave.value = on
        prefs?.edit()?.putBoolean(PREF_WAVE, on)?.apply()
    }

    fun setAnimated2D(on: Boolean) {
        animated2D.value = on
        prefs?.edit()?.putBoolean(PREF_ANIM, on)?.apply()
    }

    /** After the picture was chosen or reset in Config. */
    fun pictureChanged(ctx: Context) {
        hasPicture.value = CustomMedia.has(ctx, CustomMedia.Kind.BACKGROUND)
        pictureVersion.intValue++
    }
}

/** Custom color for the animated library "wave" background — the procedural XMB gradient drawn
 *  by [XmbGlView]. The theme only colors the UI chrome; this colors the backdrop itself. The user
 *  picks the vivid gradient stop (what fills most of the frame); the deep near-black top stop is
 *  derived from it so the gradient keeps its XMB depth. The white wave ribbon is left untinted.
 *
 *  Threading: the wave renders on XmbGlView's OWN EGL thread, not Compose's — so the shader-facing
 *  values are held as @Volatile float triplets it can read every frame without touching Compose
 *  state cross-thread. [color] backs the settings picker; [glTop]/[glBottom] feed the shader
 *  (ARMFlash: the default colour also goes to the shader, so the wave is #454859 rather than
 *  XmbGlView's built-in blue). 0 = not customized.
 *  (Ported from ARMSX2's ui/theme/Theme.kt, ARMSX2 team.) */
object LibraryBackgroundColorPreferences {
    private const val Key = "ui.library.bgColor"
    private var prefs: SharedPreferences? = null

    /** ARMFlash's wave colour when none is picked (ARMSX2's is XmbGlView.BG_BOT's blue). */
    const val DefaultDisplayColor: Int = 0xFF454859.toInt()

    /** Quick-pick palette for the settings swatches (XMB-flavored); the RGB sliders stay for custom
     *  colors. First entry is the built-in default. */
    val PRESETS: List<Int> = listOf(
        DefaultDisplayColor, // ARMFlash's default
        0xFF2E75F5.toInt(), // royal blue (ARMSX2's default)
        0xFF00B4D8.toInt(), // aqua
        0xFF19C37D.toInt(), // green
        0xFF16A085.toInt(), // teal
        0xFF9B59B6.toInt(), // purple
        0xFFE84393.toInt(), // magenta
        0xFFE74C3C.toInt(), // red
        0xFFF39C12.toInt(), // orange
        0xFFF1C40F.toInt(), // gold
        0xFFAF601A.toInt(), // bronze
        0xFF95A5A6.toInt(), // silver
        0xFF34495E.toInt(), // slate
    )

    /** Packed ARGB for the settings UI. 0 = not customized (use the built-in default). */
    val color = mutableStateOf(0)

    /** GL-thread gradient stops (linear 0..1 RGB); null only before [load]. */
    @Volatile var glTop: FloatArray? = null
        private set
    @Volatile var glBottom: FloatArray? = null
        private set

    /** When on, the wave cycles the hue wheel continuously (RGB-peripheral style), overriding the
     *  fixed color. XmbGlView reads [rgbCycleGl] straight from its GL thread. */
    private const val RgbKey = "ui.library.bgRgb"
    val rgbCycle = mutableStateOf(false)
    @Volatile var rgbCycleGl = false
        private set

    fun load(p: SharedPreferences) {
        prefs = p
        val stored = p.getInt(Key, 0)
        color.value = stored
        apply(stored)
        val rgb = p.getBoolean(RgbKey, false)
        rgbCycle.value = rgb
        rgbCycleGl = rgb
    }

    /** Set the wave color (packed ARGB; alpha forced opaque). Applies live on the next GL frame. */
    fun set(argb: Int) {
        val v = if (argb == 0) 0 else (argb or (0xFF shl 24))
        color.value = v
        prefs?.edit()?.putInt(Key, v)?.apply()
        apply(v)
    }

    /** Revert to the built-in default wave color. */
    fun reset() {
        color.value = 0
        prefs?.edit()?.remove(Key)?.apply()
        apply(0)
    }

    /** Toggle the continuous RGB hue-cycle. Applies on the next frame. */
    fun setRgbCycle(on: Boolean) {
        rgbCycle.value = on
        rgbCycleGl = on
        prefs?.edit()?.putBoolean(RgbKey, on)?.apply()
    }

    private fun apply(stored: Int) {
        val argb = if (stored == 0) DefaultDisplayColor else stored
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        glBottom = floatArrayOf(r, g, b)
        // 0.20 keeps roughly the built-in blue's top/bottom ratio: a deep, near-black anchor.
        glTop = floatArrayOf(r * 0.20f, g * 0.20f, b * 0.20f)
    }
}

/**
 * The library's own chrome over the background (ported from ARMSX2's LibraryChromePreferences,
 * ARMSX2 team): the colour of the top bar, and how opaque the bars, panels and game tiles are
 * while a background shows behind them.
 */
object LibraryChromePreferences {
    private const val BarColorKey = "ui.library.barColor"
    private const val OpacityKey = "ui.library.opacity"
    private var prefs: SharedPreferences? = null

    /** The top bar's colour; 0 = the theme's. */
    val barColor = mutableStateOf(0)

    /** Same XMB-ish quick picks the background offers, so the two read as one palette. */
    val BAR_PRESETS: List<Int> = listOf(
        0xFF2E75F5.toInt(), 0xFF16A3C8.toInt(), 0xFF19B36B.toInt(), 0xFF12907E.toInt(),
        0xFFA05BD0.toInt(), 0xFFE8458F.toInt(), 0xFFE8503A.toInt(), 0xFFF0A31E.toInt(),
        0xFFF5D020.toInt(), 0xFFB4652A.toInt(), 0xFFAFBAC0.toInt(), 0xFF33475B.toInt(),
    )

    /**
     * Bars, panels and tiles over the background, as a percent (20–100); 100 = fully opaque.
     * ARMFlash starts at 20 (ARMSX2 at 100), so the wave shows through the whole library.
     */
    val libraryOpacity = mutableStateOf(20)

    fun load(p: SharedPreferences) {
        prefs = p
        barColor.value = p.getInt(BarColorKey, 0)
        libraryOpacity.value = p.getInt(OpacityKey, 20).coerceIn(20, 100)
    }

    fun setBarColor(argb: Int) {
        barColor.value = argb
        prefs?.edit()?.putInt(BarColorKey, argb)?.apply()
    }

    fun setLibraryOpacity(value: Int) {
        val v = value.coerceIn(20, 100)
        libraryOpacity.value = v
        prefs?.edit()?.putInt(OpacityKey, v)?.apply()
    }
}

/**
 * What is drawn behind the library: the player's picture, else the XMB wave (the 2D one when
 * chosen, or when GL cannot start; the bundled still holds its place while GL starts), then a
 * scrim so text stays readable. As ARMSX2's HomeScreen backdrop.
 */
@Composable
fun LibraryBackdrop(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize()) {
        val picture = LibraryBackground.hasPicture.value
        if (picture) {
            val version = LibraryBackground.pictureVersion.intValue
            val request = remember(version) {
                coil.request.ImageRequest.Builder(ctx)
                    .data(CustomMedia.file(ctx, CustomMedia.Kind.BACKGROUND))
                    // A GIF or animated WebP plays.
                    .decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
                    .memoryCacheKey("library-background-$version").diskCachePolicy(coil.request.CachePolicy.DISABLED)
                    .crossfade(true)
                    .build()
            }
            coil.compose.AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (LibraryBackground.animated2D.value) {
            LibraryWaveBackground(Modifier.fillMaxSize())
        } else {
            var glState by remember { mutableStateOf<Boolean?>(null) } // null = starting, true = up, false = failed
            if (glState == false) {
                LibraryWaveBackground(Modifier.fillMaxSize())
            } else {
                // While GL starts: the wave's own gradient in its colour (ARMSX2 shows its blue
                // XMB still here, which would flash a colour the player did not pick).
                val c = LibraryBackgroundColorPreferences.color.value.takeIf { it != 0 } ?: LibraryBackgroundColorPreferences.DefaultDisplayColor
                val bottom = androidx.compose.ui.graphics.Color(c)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(bottom.copy(red = bottom.red * 0.2f, green = bottom.green * 0.2f, blue = bottom.blue * 0.2f), bottom)),
                    ),
                )
            }
            AndroidView(
                factory = { XmbGlView(it).apply { onGlStatus = { ok -> glState = ok } } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // A picture can be any brightness, so it gets the full scrim; the wave is dark where the
        // content sits, so it gets a light one (unless the theme is light, with dark text).
        val strong = picture || Fp.theme.value.light
        val base = Fp.theme.value.browse
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(base.copy(alpha = if (strong) 0.55f else 0.06f), base.copy(alpha = if (strong) 0.80f else 0.20f)),
                ),
            ),
        )
    }
}
