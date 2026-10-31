package com.port80.app.audio

import android.os.Process
import android.os.SystemClock
import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Processes the live PCM audio (16-bit little-endian, interleaved) right before
 * it reaches the AAC encoder:
 *
 * 5-band EQ -> compressor -> output gain -> safety limiter (-1 dBFS)
 *
 * It also:
 * - raises the audio reader thread to audio priority (avoids dropouts while
 *   the device is busy encoding video),
 * - measures the level for the on-screen meter,
 * - detects silence (no signal from the mixer), clipping, and lost samples.
 *
 * Runs on RootEncoder's microphone thread. Must never throw.
 */
class LiveAudioProcessor(
    private val sampleRate: Int,
    private val channels: Int,
) : CustomAudioEffect() {

    private val ch = channels.coerceIn(1, 2)
    private val bands = AudioFxSettings.BAND_COUNT

    // Biquad coefficients per band: b0, b1, b2, a1, a2 (a0 normalized to 1)
    private val coef = Array(bands) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }
    private val bandActive = BooleanArray(bands)
    // Filter state per channel and band: z1, z2 (transposed direct form II)
    private val z1 = Array(ch) { DoubleArray(bands) }
    private val z2 = Array(ch) { DoubleArray(bands) }

    private var applied: AudioFxSettings? = null
    private var eqOn = false
    private var compOn = false
    private var limiterOn = true
    private var thresholdDb = -18.0
    private var ratio = 3.0
    private var makeupDb = 0.0
    private var outputGain = 1.0

    private val attackCoeff = exp(-1.0 / (0.010 * sampleRate))
    private val releaseCoeff = exp(-1.0 / (0.150 * sampleRate))
    private val limiterReleaseCoeff = exp(-1.0 / (0.080 * sampleRate))
    private var compGrDb = 0.0
    private var limiterGain = 1.0

    private val frameValues = DoubleArray(ch)

    // Monitoring
    private var priorityRaised = false
    private var startMs = 0L
    private var framesSinceStart = 0L
    private var windowStartMs = 0L
    private var windowMinLostMs = Double.MAX_VALUE
    private var baselineLostMs = Double.NaN
    private var silentSinceMs = 0L
    private var clipUntilMs = 0L
    private var meterDb = -90.0
    private var lastPublishMs = 0L
    private var lastRateCheckMs = 0L
    private var lastMeterMs = 0L
    private var rateMismatch = false

    override fun process(pcmBuffer: ByteArray): ByteArray {
        try {
            raisePriorityOnce()
            val now = SystemClock.elapsedRealtime()
            val frames = pcmBuffer.size / (2 * ch)
            trackLostSamples(now, frames)
            refreshSettings()

            var inputPeak = 0
            var outputPeak = 0.0
            var i = 0
            for (f in 0 until frames) {
                // Read one interleaved frame and run the EQ per channel
                var framePeak = 0.0
                for (c in 0 until ch) {
                    val lo = pcmBuffer[i + 2 * c].toInt() and 0xFF
                    val hi = pcmBuffer[i + 2 * c + 1].toInt()
                    val s = (hi shl 8) or lo
                    val absS = abs(s)
                    if (absS > inputPeak) inputPeak = absS
                    var x = s / 32768.0
                    if (eqOn) x = runEq(c, x)
                    frameValues[c] = x
                    val ax = abs(x)
                    if (ax > framePeak) framePeak = ax
                }

                // Compressor (stereo-linked)
                var gain = outputGain
                if (compOn) {
                    val levelDb = 20.0 * log10(max(framePeak, 1e-6))
                    val over = levelDb - thresholdDb
                    val target = if (over > 0) over * (1.0 / ratio - 1.0) else 0.0
                    val k = if (target < compGrDb) attackCoeff else releaseCoeff
                    compGrDb = k * compGrDb + (1 - k) * target
                    gain *= 10.0.pow((compGrDb + makeupDb) / 20.0)
                }

                // Safety limiter at -1 dBFS
                if (limiterOn) {
                    limiterGain = 1.0 - (1.0 - limiterGain) * limiterReleaseCoeff
                    val peakAfter = framePeak * gain * limiterGain
                    if (peakAfter > LIMIT_CEILING) {
                        limiterGain = LIMIT_CEILING / max(framePeak * gain, 1e-9)
                    }
                    gain *= limiterGain
                }

                for (c in 0 until ch) {
                    var y = frameValues[c] * gain
                    if (y > 0.99997) y = 0.99997 else if (y < -1.0) y = -1.0
                    val ay = abs(y)
                    if (ay > outputPeak) outputPeak = ay
                    val out = (y * 32768.0).toInt()
                    pcmBuffer[i + 2 * c] = (out and 0xFF).toByte()
                    pcmBuffer[i + 2 * c + 1] = ((out shr 8) and 0xFF).toByte()
                }
                i += 2 * ch
            }

            updateMonitoring(now, inputPeak, outputPeak)
        } catch (_: Throwable) {
            // Never break the audio thread; worst case the buffer passes through.
        }
        // RootEncoder reuses the same array for every read while earlier frames
        // may still be waiting in the encoder queue. Returning a private copy
        // keeps each queued frame intact (otherwise audio gets overwritten).
        return pcmBuffer.copyOf()
    }

    private fun runEq(c: Int, input: Double): Double {
        var x = input
        val zc1 = z1[c]
        val zc2 = z2[c]
        for (b in 0 until bands) {
            if (!bandActive[b]) continue
            val k = coef[b]
            val y = k[0] * x + zc1[b]
            zc1[b] = k[1] * x - k[3] * y + zc2[b]
            zc2[b] = k[2] * x - k[4] * y
            x = y
        }
        return x
    }

    private fun refreshSettings() {
        val s = AudioFxStore.settings.value
        if (s === applied) return
        applied = s
        eqOn = s.eqEnabled && s.eqGainsDb.any { it != 0f }
        for (b in 0 until bands) {
            val g = s.eqGainsDb.getOrElse(b) { 0f }.toDouble()
            bandActive[b] = g != 0.0
            if (bandActive[b]) computeBiquad(b, g)
        }
        val preset = CompressorPreset.of(s.compressorPreset)
        compOn = preset != CompressorPreset.OFF
        thresholdDb = preset.thresholdDb.toDouble()
        ratio = max(1.0, preset.ratio.toDouble())
        makeupDb = preset.makeupDb.toDouble()
        outputGain = 10.0.pow(s.outputGainDb / 20.0)
        limiterOn = s.limiterEnabled
        if (!compOn) compGrDb = 0.0
        if (!limiterOn) limiterGain = 1.0
    }

    /** RBJ audio-EQ-cookbook filters. */
    private fun computeBiquad(band: Int, gainDb: Double) {
        val nyquistSafe = sampleRate * 0.45
        val f0 = min(AudioFxSettings.BAND_FREQS_HZ[band].toDouble(), nyquistSafe)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (band) {
            0 -> { // low shelf, S = 1
                val alpha = sw / 2.0 * sqrt(2.0)
                val sa = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + sa)
                b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - sa)
                a0 = (a + 1) + (a - 1) * cw + sa
                a1 = -2 * ((a - 1) + (a + 1) * cw)
                a2 = (a + 1) + (a - 1) * cw - sa
            }
            bands - 1 -> { // high shelf, S = 1
                val alpha = sw / 2.0 * sqrt(2.0)
                val sa = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + sa)
                b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - sa)
                a0 = (a + 1) - (a - 1) * cw + sa
                a1 = 2 * ((a - 1) - (a + 1) * cw)
                a2 = (a + 1) - (a - 1) * cw - sa
            }
            else -> { // peaking, Q = 1
                val alpha = sw / 2.0
                b0 = 1 + alpha * a
                b1 = -2 * cw
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cw
                a2 = 1 - alpha / a
            }
        }
        val k = coef[band]
        k[0] = b0 / a0; k[1] = b1 / a0; k[2] = b2 / a0; k[3] = a1 / a0; k[4] = a2 / a0
    }

    private fun raisePriorityOnce() {
        if (priorityRaised) return
        priorityRaised = true
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Compares wall-clock time with the amount of audio received. Temporary
     * delays catch up again; real sample loss raises the baseline for good.
     * The baseline is the minimum "lost time" seen in each 2 s window.
     */
    private fun trackLostSamples(now: Long, frames: Int) {
        if (startMs == 0L) {
            startMs = now
            windowStartMs = now
            framesSinceStart = 0
            return
        }
        framesSinceStart += frames
        val receivedMs = framesSinceStart * 1000.0 / sampleRate
        val lostMs = (now - startMs) - receivedMs
        checkRealSampleRate(now)
        if (lostMs < windowMinLostMs) windowMinLostMs = lostMs
        if (now - windowStartMs >= 2000) {
            val base = baselineLostMs
            if (!rateMismatch && !base.isNaN() && windowMinLostMs - base > CUT_THRESHOLD_MS) {
                AudioMonitor.addCut(now)
            }
            // Never let the baseline go down by a single noisy window
            baselineLostMs = if (base.isNaN()) windowMinLostMs else max(base, windowMinLostMs)
            windowStartMs = now
            windowMinLostMs = Double.MAX_VALUE
        }
    }

    /**
     * After 10 s, compares the real number of samples per second with the
     * configured rate. A mixer that really runs at 44.1 kHz while 48 kHz was
     * requested produces "chipmunk" audio and constant gaps on YouTube.
     */
    private fun checkRealSampleRate(now: Long) {
        val elapsed = now - startMs
        if (elapsed < 10_000 || now - lastRateCheckMs < 5_000) return
        lastRateCheckMs = now
        val measured = (framesSinceStart * 1000.0 / elapsed).toInt()
        val deviation = abs(measured - sampleRate).toDouble() / sampleRate
        var suggested = 0
        if (deviation > 0.03) {
            val nearest = STANDARD_RATES.minByOrNull { abs(it - measured) } ?: sampleRate
            if (nearest != sampleRate && abs(nearest - measured).toDouble() / nearest < 0.015) {
                suggested = nearest
            }
        }
        rateMismatch = suggested != 0
        AudioMonitor.setMeasuredRate(measured, suggested)
    }

    private fun updateMonitoring(now: Long, inputPeak: Int, outputPeak: Double) {
        val inputDb = if (inputPeak > 0) 20.0 * log10(inputPeak / 32768.0) else -90.0
        if (inputDb < SILENCE_DB) {
            if (silentSinceMs == 0L) silentSinceMs = now
        } else {
            silentSinceMs = 0L
        }
        val silent = silentSinceMs != 0L && now - silentSinceMs >= SILENCE_MS
        if (inputPeak >= 32700) clipUntilMs = now + 3000
        val clipping = now < clipUntilMs

        val outDb = if (outputPeak > 0) 20.0 * log10(outputPeak) else -90.0
        val elapsed = if (lastMeterMs == 0L) 0L else now - lastMeterMs
        lastMeterMs = now
        meterDb = max(outDb, meterDb - elapsed * 0.02) // ~20 dB/s decay
        if (now - lastPublishMs >= 100) {
            lastPublishMs = now
            AudioMonitor.publishLevels(meterDb.toFloat().coerceAtLeast(-90f), silent, clipping)
        }
    }

    private companion object {
        const val LIMIT_CEILING = 0.891 // -1 dBFS
        const val CUT_THRESHOLD_MS = 50.0
        const val SILENCE_DB = -50.0
        const val SILENCE_MS = 8000L
        val STANDARD_RATES = intArrayOf(8000, 16000, 22050, 32000, 44100, 48000)
    }
}
