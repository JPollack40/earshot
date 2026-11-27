package com.earshot.app.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.earshot.app.R
import com.google.common.collect.ImmutableList
import java.util.Locale
import kotlin.math.abs

/**
 * The extra buttons on the playing screen in Android Auto (and in the phone's media notification):
 * AI sweep (phase 14), Bookmark and Mark played, custom session commands handled by the service. Speed lives in its own list in the
 * car (a row at the top of the Queue tab); there's no sleep timer there (the listener is driving).
 */
object PlayerButtons {
    const val MARK_PLAYED = "com.earshot.app.MARK_PLAYED"
    const val BOOKMARK = "com.earshot.app.BOOKMARK"
    /** Phase 13: "Skip ad" in the forward slot while a found ad plays (ads set to Manual). */
    const val SKIP_AD = "com.earshot.app.SKIP_AD"

    val markPlayedCommand = SessionCommand(MARK_PLAYED, Bundle.EMPTY)
    val bookmarkCommand = SessionCommand(BOOKMARK, Bundle.EMPTY)
    val skipAdCommand = SessionCommand(SKIP_AD, Bundle.EMPTY)
    /** Phase 14: "AI sweep" (broom): transcript, chapters and ads for the playing episode in one go. */
    const val AI_SWEEP = "com.earshot.app.AI_SWEEP"
    val aiSweepCommand = SessionCommand(AI_SWEEP, Bundle.EMPTY)

    /** "1.3x", "2x" */
    fun speedLabel(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

    /** Icons showing a speed ("1.3x"), for the speed list in the car. */
    private val SPEED_ICONS = mapOf(
        0.8f to R.drawable.ic_speed_0_8,
        1.0f to R.drawable.ic_speed_1,
        1.1f to R.drawable.ic_speed_1_1,
        1.2f to R.drawable.ic_speed_1_2,
        1.3f to R.drawable.ic_speed_1_3,
        1.4f to R.drawable.ic_speed_1_4,
        1.5f to R.drawable.ic_speed_1_5,
        1.7f to R.drawable.ic_speed_1_7,
        2.0f to R.drawable.ic_speed_2,
        2.5f to R.drawable.ic_speed_2_5,
        3.0f to R.drawable.ic_speed_3,
    )

    fun speedIcon(speed: Float): Int =
        SPEED_ICONS.entries.firstOrNull { abs(it.key - speed) < 0.01f }?.value ?: R.drawable.ic_speed

    /**
     * Skip back and forward in their usual places, then Bookmark and Mark played. [bookmarked] shows
     * the filled bookmark for a moment after a tap, so the driver can see it worked. [skipAd] swaps
     * the forward button for Skip ad.
     */
    @OptIn(UnstableApi::class)
    fun all(backMs: Long, forwardMs: Long, bookmarked: Boolean = false, skipAd: Boolean = false): ImmutableList<CommandButton> {
        val skips = skipButtons(backMs, forwardMs)
        return ImmutableList.builder<CommandButton>()
            .add(skips[0])
            .add(
                if (skipAd) {
                    // Phase 13: while a found ad plays (Manual ad skipping), forward becomes Skip ad.
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setSessionCommand(skipAdCommand)
                        .setDisplayName("Skip ad")
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build()
                } else {
                    skips[1]
                },
            )
            .add(
                // Phase 14: AI sweep, left of Bookmark. A second tap while one runs for the episode does nothing.
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(R.drawable.ic_ai_sweep)
                    .setSessionCommand(aiSweepCommand)
                    .setDisplayName("AI sweep")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            .add(
                CommandButton.Builder(if (bookmarked) CommandButton.ICON_BOOKMARK_FILLED else CommandButton.ICON_BOOKMARK_UNFILLED)
                    .setSessionCommand(bookmarkCommand)
                    .setDisplayName(if (bookmarked) "Bookmarked" else "Bookmark")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            .add(
                CommandButton.Builder(CommandButton.ICON_CHECK_CIRCLE_UNFILLED)
                    .setSessionCommand(markPlayedCommand)
                    .setDisplayName("Mark played")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            .build()
    }
}
