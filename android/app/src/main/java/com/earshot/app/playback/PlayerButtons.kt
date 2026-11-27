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
 * Speed, Sleep timer and Mark played. Each tap is a custom session command handled by the service.
 */
object PlayerButtons {
    const val SPEED = "com.earshot.app.SPEED"
    const val SLEEP = "com.earshot.app.SLEEP"
    const val MARK_PLAYED = "com.earshot.app.MARK_PLAYED"

    val speedCommand = SessionCommand(SPEED, Bundle.EMPTY)
    val sleepCommand = SessionCommand(SLEEP, Bundle.EMPTY)
    val markPlayedCommand = SessionCommand(MARK_PLAYED, Bundle.EMPTY)

    /** The speeds the Speed button steps through, wrapping back to 1x. */
    val SPEED_STEPS = listOf(1.0f, 1.2f, 1.3f, 1.5f, 1.7f, 2.0f)

    fun nextSpeed(current: Float): Float = SPEED_STEPS.firstOrNull { it > current + 0.01f } ?: SPEED_STEPS.first()

    /** "1.3x", "2x" */
    fun speedLabel(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

    /** Sleep timer steps: Off, 15, 30, 60 minutes, end of episode, Off. */
    fun nextSleep(current: SleepTimer, now: Long = System.currentTimeMillis()): SleepStep = when (current) {
        SleepTimer.Off -> SleepStep.Minutes(15)
        SleepTimer.EndOfEpisode -> SleepStep.Off
        is SleepTimer.At -> {
            val left = current.endsAtMs - now
            when {
                left <= 15 * 60_000L -> SleepStep.Minutes(30)
                left <= 30 * 60_000L -> SleepStep.Minutes(60)
                else -> SleepStep.EndOfEpisode
            }
        }
    }

    sealed interface SleepStep {
        data object Off : SleepStep
        data class Minutes(val minutes: Int) : SleepStep
        data object EndOfEpisode : SleepStep
    }

    /** Skip back and forward in their usual places, then Speed, Sleep and Mark played. */
    @OptIn(UnstableApi::class)
    fun all(backMs: Long, forwardMs: Long, speed: Float, sleepLabel: String): ImmutableList<CommandButton> {
        val speedIcon = when {
            abs(speed - 1.0f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            abs(speed - 1.2f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            abs(speed - 1.5f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            abs(speed - 1.8f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            abs(speed - 2.0f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
            else -> CommandButton.ICON_PLAYBACK_SPEED
        }
        return ImmutableList.builder<CommandButton>()
            .addAll(skipButtons(backMs, forwardMs))
            .add(
                CommandButton.Builder(speedIcon)
                    .setCustomIconResId(R.drawable.ic_speed)
                    .setSessionCommand(speedCommand)
                    .setDisplayName("Speed ${speedLabel(speed)}")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            .add(
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(R.drawable.ic_sleep)
                    .setSessionCommand(sleepCommand)
                    .setDisplayName(sleepLabel)
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
