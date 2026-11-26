package com.earshot.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Voice Boost: evens out loudness so quiet shows and quiet voices are easier to hear.
 *
 * Three stages on 16-bit PCM:
 * 1. Slow automatic gain (time constant about 3 s) that moves the average speech level toward
 *    [TARGET_RMS], between -6 dB and +12 dB. Near-silence (below [GATE_RMS]) doesn't count, so
 *    pauses and room noise aren't pulled up.
 * 2. A fast compressor (3:1 above [COMP_THRESHOLD]) that tames loud peaks so the overall level can
 *    sit higher.
 * 3. A soft limiter so the output never clips.
 *
 * Always "active" in the audio chain so it can be switched on and off mid-episode without
 * reconfiguring the player; when [enabled] is false it passes audio through untouched.
 */
@OptIn(UnstableApi::class)
class VoiceBoostProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled = false

    private var channels = 1
    private var sampleRate = 44_100

    // Stage 1: slow loudness tracking
    private var meanSquare = TARGET_RMS * TARGET_RMS
    private var agcGain = 1f
    private var msCoef = 0f

    // Stage 2: compressor envelope
    private var envelope = 0f
    private var attackCoef = 0f
    private var releaseCoef = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        msCoef = coef(3.0f)
        attackCoef = coef(0.005f)
        releaseCoef = coef(0.2f)
        return inputAudioFormat
    }

    /** Per-frame smoothing coefficient for a time constant of [seconds]. */
    private fun coef(seconds: Float) = exp(-1.0 / (seconds * sampleRate)).toFloat()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = size / (2 * channels)
        val frame = FloatArray(channels)
        repeat(frames) {
            // Read one frame, find its peak and power.
            var peak = 0f
            var power = 0f
            for (c in 0 until channels) {
                val s = input.getShort() / 32768f
                frame[c] = s
                val a = abs(s)
                if (a > peak) peak = a
                power += s * s
            }
            power /= channels

            // 1. Slow loudness tracking, ignoring near-silence.
            if (power > GATE_RMS * GATE_RMS) {
                meanSquare = msCoef * meanSquare + (1 - msCoef) * power
                agcGain = (TARGET_RMS / sqrt(meanSquare)).coerceIn(MIN_GAIN, MAX_GAIN)
            }

            // 2. Compressor on the gained signal.
            val level = peak * agcGain
            envelope = if (level > envelope) attackCoef * envelope + (1 - attackCoef) * level
            else releaseCoef * envelope + (1 - releaseCoef) * level
            val compGain = if (envelope > COMP_THRESHOLD) (envelope / COMP_THRESHOLD).pow(1f / COMP_RATIO - 1f) else 1f
            val gain = agcGain * compGain

            // 3. Soft limiter, then back to 16-bit.
            for (c in 0 until channels) {
                val y = limit(frame[c] * gain)
                out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        // Any partial frame left over (shouldn't happen) is passed through.
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))
        return if (x < 0) -y else y
    }

    override fun onReset() {
        meanSquare = TARGET_RMS * TARGET_RMS
        agcGain = 1f
        envelope = 0f
    }

    private companion object {
        const val TARGET_RMS = 0.1f // about -20 dBFS
        const val GATE_RMS = 0.006f // about -44 dBFS
        const val MIN_GAIN = 0.5f // -6 dB
        const val MAX_GAIN = 4f // +12 dB
        const val COMP_THRESHOLD = 0.25f // about -12 dBFS
        const val COMP_RATIO = 3f
        const val KNEE = 0.85f
    }
}
