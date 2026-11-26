package com.earshot.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * The player's audio processing: Smart Speed (silence shortening), then Voice Boost, then speed
 * change. Like Media3's DefaultAudioProcessorChain, except Voice Boost runs after silence
 * detection, so boosted room noise is still recognised as silence.
 */
@OptIn(UnstableApi::class)
class SmartAudioChain : AudioProcessorChain {
    /**
     * Smart Speed. Pauses longer than 0.15 s are cut to a quarter of their length, and never left
     * longer than 0.5 s, so speech keeps a natural rhythm but dead air goes.
     */
    val silence = SilenceSkippingAudioProcessor(
        /* minimumSilenceDurationUs = */ 150_000,
        /* silenceRetentionRatio = */ 0.25f,
        /* maxSilenceToKeepDurationUs = */ 500_000,
        /* minVolumeToKeepPercentageWhenMuting = */ SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
        /* silenceThresholdLevel = */ SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
    )
    val voiceBoost = VoiceBoostProcessor()
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(silence, voiceBoost, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silence.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silence.skippedFrames
}
