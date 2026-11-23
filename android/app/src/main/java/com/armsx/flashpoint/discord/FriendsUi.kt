package com.armsx.flashpoint.discord

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/*
 * ARMSX2's Friends pieces (ui/friends/FriendsScreen.kt), ported: a friend's row, the
 * "<name> is now online" banner and the signed-in account chip. Same layout and behaviour; the
 * cover is the game's Flashpoint logo instead of a PS2 cover.
 */

/**
 * One friend: who they are, and what they are playing.
 *
 * Avatar AND game art, never one instead of the other: the avatar is who they are and the art is
 * what they are doing. In the library there is no art, so the row is the avatar and the name.
 */
@Composable
fun FriendRow(friend: DiscordFriend, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (friend.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = friend.avatarUrl,
                contentDescription = friend.name,
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.width(10.dp))
        friend.coverUrl?.let { art ->
            AsyncImage(
                model = art,
                contentDescription = friend.game,
                modifier = Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            Text(
                friend.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A game we could not name still is not the library: "In a game", never idle.
                when {
                    friend.inLibrary -> "In the library"
                    friend.game.isBlank() -> "In a game"
                    friend.serial.isNotBlank() -> "${friend.game} · ${friend.serial}"
                    else -> friend.game
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "<name> is now online", with their avatar, over whatever is on screen (the library or a game).
 * Self-dismissing: nothing here is worth a button to get rid of.
 */
@Composable
fun FriendOnlineBanner(modifier: Modifier = Modifier) {
    val friend by DiscordPresence.justOnline

    // Keyed on the friend, so a second arrival during the first banner restarts the clock.
    LaunchedEffect(friend) {
        if (friend != null) {
            kotlinx.coroutines.delay(4200)
            DiscordPresence.justOnline.value = null
        }
    }

    AnimatedVisibility(
        visible = friend != null,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
    ) {
        // Held separately so the exit animation still has something to draw after the state clears.
        val shown = remember(friend) { friend }
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            shadowElevation = 14.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!shown?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = shown?.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    shown?.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (shown != null && !shown.inLibrary && shown.game.isNotBlank()) "is playing ${shown.game}" else "is now online",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** The signed-in account, compact: avatar and name. Nothing when not connected. */
@Composable
fun SelfChip(modifier: Modifier = Modifier) {
    val me by DiscordPresence.self
    val status by DiscordPresence.status
    val shown = me
    if (status != DiscordPresence.CONNECTED || shown == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.avatarUrl.takeIf { it.isNotBlank() }?.let { avatar ->
            AsyncImage(
                model = avatar,
                contentDescription = shown.name,
                modifier = Modifier.size(24.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            shown.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
