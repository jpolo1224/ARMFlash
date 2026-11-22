// Ported from ARMSX2's RecentGamesContentProvider.kt; contributed by misantronic (ARMSX2 PR #566),
// opt-in gate added on merge.
package com.armsx.flashpoint.data.recent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.armsx.flashpoint.Frontend
import com.armsx.flashpoint.core.FpCore
import com.armsx.flashpoint.core.SearchOpts

/**
 * The recently played list for companion apps and frontends, over Binder IPC (no storage
 * permission on either side), as ARMSX2 serves it. Each row's `uri` is the game's
 * armflash://play/<id> link, which starts the game when opened.
 *
 * Sharing is opt-in per calling app: [RecentGamesAccessActivity] asks the user once, and the
 * package Binder reports for the caller is what gets the grant. Without one the cursor is empty,
 * with extras saying why.
 */
class RecentGamesContentProvider : ContentProvider() {
    companion object {
        const val KEY_SHARE_ENABLED = "library.shareRecentGames"
        const val EXTRA_ACCESS_DENIED = "com.armsx.flasharm.extra.RECENT_GAMES_ACCESS_DENIED"
        const val EXTRA_CONSENT_DECLINED = "com.armsx.flasharm.extra.RECENT_GAMES_CONSENT_DECLINED"
        const val COLUMN_URI = "uri"
        const val COLUMN_TITLE = "title"
        const val COLUMN_SERIAL = "serial"
        const val COLUMN_EXT = "ext"
        const val COLUMN_PLATFORM = "platform"
        const val COLUMN_LAST_PLAYED = "lastPlayed"
        private const val MAX_RECENT = 12
        private val COLUMNS = arrayOf(COLUMN_URI, COLUMN_TITLE, COLUMN_SERIAL, COLUMN_EXT, COLUMN_PLATFORM, COLUMN_LAST_PLAYED)
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        val prefs = RecentGamesAccess.prefs(context)
        val allowed = prefs.getBoolean(KEY_SHARE_ENABLED, false) || RecentGamesAccess.isGranted(prefs, callingPackage)
        if (!allowed) {
            cursor.extras = Bundle().apply {
                putBoolean(EXTRA_ACCESS_DENIED, true)
                putBoolean(EXTRA_CONSENT_DECLINED, RecentGamesAccess.isDeclined(prefs, callingPackage))
            }
            return cursor
        }
        // The provider can be asked before ARMFlash's screen ever opened: open the database here.
        val library = (context.applicationContext as com.armsx.flashpoint.FpApp).library
        if (!library.database.isFile) return cursor
        runCatching {
            FpCore.open(library.database.path)
            FpCore.page("", SearchOpts(played = true, order = "LASTPLAYED", descending = true), MAX_RECENT)
        }.getOrNull().orEmpty().forEach { g ->
            val last = g.lastPlayed?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            cursor.addRow(arrayOf<Any?>(Frontend.playUri(g.id).toString(), g.title, g.id, Frontend.EXT, g.primaryPlatform, last))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
