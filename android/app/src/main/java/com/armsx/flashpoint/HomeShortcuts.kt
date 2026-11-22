package com.armsx.flashpoint

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pin a game to the Android home screen, ported from ARMSX2's HomeShortcuts (ARMSX2 team). The
 * shortcut opens the game's `armflash://play/<id>` link, so tapping it boots straight into the
 * game. Its icon is the game's own Flashpoint logo (never the app icon unless the game has no
 * art), decoded and clamped to the launcher's icon limit first: an oversized bitmap makes the
 * pin fail silently, which is what ARMSX2's #335 was.
 */
object HomeShortcuts {
    private const val TAG = "HomeShortcuts"
    private const val FALLBACK_MAX_ICON_PX = 128

    fun isSupported(ctx: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) }.getOrDefault(false)

    /** Ask the launcher to add a shortcut for the game; false when nothing could be requested. */
    suspend fun pin(ctx: Context, id: String, title: String, logoUrl: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Frontend.playUri(id)).setClass(ctx, BootActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val label = title.ifBlank { "Flashpoint game" }
        val icon = withContext(Dispatchers.IO) { loadArt(ctx, logoUrl, maxIconEdgePx(ctx)) }?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(ctx, ctx.applicationInfo.icon)
        return runCatching {
            val shortcut = ShortcutInfoCompat.Builder(ctx, "game:$id")
                .setShortLabel(label.take(20))
                .setLongLabel(label.take(40))
                .setIcon(icon)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
        }.getOrElse { e ->
            Log.w(TAG, "pin failed for '$label': $e")
            false
        }
    }

    /** The game's art through the app's image loader (its disk cache), on a square canvas. */
    private suspend fun loadArt(ctx: Context, url: String, maxPx: Int): Bitmap? = try {
        val request = ImageRequest.Builder(ctx)
            .data(url)
            .size(maxPx)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        (ctx.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()?.let { square(clampToMax(it, maxPx)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "art unusable: $url: $e")
        null
    }

    /** Centre the art on a transparent square so a wide logo is not stretched by the launcher. */
    private fun square(bmp: Bitmap): Bitmap {
        if (bmp.width == bmp.height) return bmp
        val edge = max(bmp.width, bmp.height)
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(bmp, (edge - bmp.width) / 2f, (edge - bmp.height) / 2f, null)
        return out
    }

    private fun maxIconEdgePx(ctx: Context): Int {
        runCatching {
            val sm = ctx.getSystemService(ShortcutManager::class.java)
            minOf(sm.iconMaxWidth, sm.iconMaxHeight).takeIf { it > 0 }
        }.getOrNull()?.let { return it }
        runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).launcherLargeIconSize.takeIf { it > 0 }
        }.getOrNull()?.let { return it }
        return FALLBACK_MAX_ICON_PX
    }

    private fun clampToMax(bmp: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxPx) return bmp
        val ratio = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * ratio).roundToInt()), max(1, (bmp.height * ratio).roundToInt()), true)
    }
}
