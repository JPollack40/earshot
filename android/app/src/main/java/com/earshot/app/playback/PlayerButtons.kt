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
 * Speed and Mark played. Each tap is a custom session command handled by the service. (No sleep
 * timer: Serena's call, since in the car the listener is driving.)
 */
object PlayerButtons {
    const val SPEED = "com.earshot.app.SPEED"
    const val MARK_PLAYED = "com.earshot.app.MARK_PLAYED"

    val speedCommand = SessionCommand(SPEED, Bundle.EMPTY)
    val markPlayedCommand = SessionCommand(MARK_PLAYED, Bundle.EMPTY)

    /** The speeds the Speed button steps through, wrapping back to 1x. */
    val SPEED_STEPS = listOf(1.0f, 1.2f, 1.3f, 1.5f, 1.7f, 2.0f)

    fun nextSpeed(current: Float): Float = SPEED_STEPS.firstOrNull { it > current + 0.01f } ?: SPEED_STEPS.first()

    /** "1.3x", "2x" */
    fun speedLabel(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

    /**
     * Android Auto shows only a button's icon on the playing screen (no label, no menus), so the
     * Speed icon shows the current speed ("1.3x"). Also used for the speed list in the car.
     */
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

    /** Skip back and forward in their usual places, then Speed and Mark played. */
    @OptIn(UnstableApi::class)
    fun all(
        backMs: Long,
        forwardMs: Long,
        speed: Float,
    ): ImmutableList<CommandButton> {
        return ImmutableList.builder<CommandButton>()
            .addAll(skipButtons(backMs, forwardMs))
            .add(
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(speedIcon(speed))
                    .setSessionCommand(speedCommand)
                    .setDisplayName("Speed ${speedLabel(speed)}")
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
