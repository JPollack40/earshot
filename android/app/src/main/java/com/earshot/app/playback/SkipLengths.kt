package com.earshot.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList

/**
 * The session's player. Skip back / forward use the current skip lengths (which can change at any
 * time, per podcast) instead of ExoPlayer's fixed increments. Everything that skips goes through
 * here: the app, the notification, the lock screen, headset buttons and Android Auto.
 */
@OptIn(UnstableApi::class)
class SkipLengthPlayer(player: Player) : ForwardingPlayer(player) {
    @Volatile var backMs = 15_000L
    @Volatile var forwardMs = 30_000L

    override fun getSeekBackIncrement(): Long = backMs
    override fun getSeekForwardIncrement(): Long = forwardMs

    override fun seekBack() {
        seekTo((currentPosition - backMs).coerceAtLeast(0))
    }

    override fun seekForward() {
        val target = currentPosition + forwardMs
        val d = duration
        seekTo(if (d != C.TIME_UNSET) target.coerceAtMost(d) else target)
    }
}

/** "15 seconds", "1 minute", "5 minutes" */
fun skipLengthName(ms: Long): String {
    val s = ms / 1000
    return when {
        s % 60 == 0L && s >= 60 -> if (s == 60L) "1 minute" else "${s / 60} minutes"
        else -> "$s seconds"
    }
}

/** Short form for buttons: "15", "1m", "5m" */
fun skipLengthShort(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60 && s % 60 == 0L) "${s / 60}m" else "$s"
}

/**
 * Skip back / forward in the previous / next slots of the notification and Android Auto. Media3
 * has numbered icons only for 5, 10, 15 and 30 seconds; other lengths get the plain skip icon,
 * with the length in the button's name.
 */
@OptIn(UnstableApi::class)
fun skipButtons(backMs: Long, forwardMs: Long): ImmutableList<CommandButton> {
    val backIcon = when (backMs) {
        5_000L -> CommandButton.ICON_SKIP_BACK_5
        10_000L -> CommandButton.ICON_SKIP_BACK_10
        15_000L -> CommandButton.ICON_SKIP_BACK_15
        30_000L -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }
    val forwardIcon = when (forwardMs) {
        5_000L -> CommandButton.ICON_SKIP_FORWARD_5
        10_000L -> CommandButton.ICON_SKIP_FORWARD_10
        15_000L -> CommandButton.ICON_SKIP_FORWARD_15
        30_000L -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }
    return ImmutableList.of(
        CommandButton.Builder(backIcon)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back ${skipLengthName(backMs)}")
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(forwardIcon)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward ${skipLengthName(forwardMs)}")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )
}
