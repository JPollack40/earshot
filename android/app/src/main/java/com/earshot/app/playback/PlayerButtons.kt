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
 * The extra button on the playing screen in Android Auto (and in the phone's media notification):
 * Mark played, a custom session command handled by the service. Speed lives in its own list in the
 * car (a row at the top of the Queue tab); there's no sleep timer there (the listener is driving).
 */
object PlayerButtons {
    const val MARK_PLAYED = "com.earshot.app.MARK_PLAYED"

    val markPlayedCommand = SessionCommand(MARK_PLAYED, Bundle.EMPTY)

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

    /** Skip back and forward in their usual places, then Mark played. */
    @OptIn(UnstableApi::class)
    fun all(backMs: Long, forwardMs: Long): ImmutableList<CommandButton> {
        return ImmutableList.builder<CommandButton>()
            .addAll(skipButtons(backMs, forwardMs))
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
