package com.earshot.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks Voice Boost on synthetic audio, so it can be verified without hunting for a suitable episode:
 * a quiet "voice" should come up close to a normal one, loud audio should come down, and switched off
 * it must leave audio untouched.
 */
class VoiceBoostProcessorTest {
    private val rate = 44_100

    /** Runs [seconds] of a 300 Hz tone at [amplitude] through the processor; returns the output level (dBFS) over the last second. */
    private fun run(p: VoiceBoostProcessor, amplitude: Float, seconds: Int): Double {
        val frames = rate * seconds
        val input = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) input.putShort((amplitude * 32767 * sin(2 * PI * 300 * i / rate)).toInt().toShort())
        input.flip()
        val out = ArrayList<Short>(frames)
        // Feed in 10 ms chunks, like the player does.
        val chunk = rate / 100 * 2
        while (input.hasRemaining()) {
            val slice = input.slice().order(ByteOrder.nativeOrder())
            val n = minOf(chunk, input.remaining())
            slice.limit(n)
            input.position(input.position() + n)
            p.queueInput(slice)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) out += o.getShort()
        }
        val last = out.takeLast(rate)
        val rms = sqrt(last.sumOf { (it / 32768.0) * (it / 32768.0) } / last.size)
        return 20 * log10(rms)
    }

    private fun processor(enabled: Boolean) = VoiceBoostProcessor().apply {
        this.enabled = enabled
        configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun db(amplitude: Float) = 20 * log10(amplitude / sqrt(2.0))

    @Test
    fun offLeavesAudioUntouched() {
        assertEquals(db(0.02f), run(processor(enabled = false), 0.02f, 3), 0.1)
    }

    @Test
    fun quietVoiceIsRaisedTowardTarget() {
        // A quiet voice around -37 dBFS (like a soft phone-in guest) should gain about 12 dB, the maximum.
        val inDb = db(0.02f)
        val outDb = run(processor(enabled = true), 0.02f, 15)
        println("quiet: in %.1f dBFS, out %.1f dBFS".format(inDb, outDb))
        assertTrue("gain was ${outDb - inDb} dB", outDb - inDb > 10)
    }

    @Test
    fun normalVoiceLandsNearTarget() {
        // -26 dBFS speech should end up near the -20 dBFS target.
        val outDb = run(processor(enabled = true), 0.07f, 15)
        println("normal: out %.1f dBFS".format(outDb))
        assertEquals(-20.0, outDb, 2.0)
    }

    @Test
    fun loudAudioIsBroughtDown() {
        val inDb = db(0.7f)
        val outDb = run(processor(enabled = true), 0.7f, 15)
        println("loud: in %.1f dBFS, out %.1f dBFS".format(inDb, outDb))
        assertTrue("change was ${outDb - inDb} dB", outDb < inDb - 4)
    }

    @Test
    fun quietAndNormalEndUpClose() {
        val quiet = run(processor(enabled = true), 0.03f, 15)
        val normal = run(processor(enabled = true), 0.1f, 15)
        println("quiet %.1f vs normal %.1f dBFS".format(quiet, normal))
        // Without Voice Boost these differ by 10.5 dB.
        assertTrue("difference was ${normal - quiet} dB", normal - quiet < 3)
    }

    @Test
    fun nearSilenceIsNotPulledUp() {
        // Room noise at -55 dBFS sits below the gate, so it must not be boosted by more than the starting gain.
        val inDb = db(0.0025f)
        val outDb = run(processor(enabled = true), 0.0025f, 10)
        assertEquals(inDb, outDb, 1.0)
    }
}
