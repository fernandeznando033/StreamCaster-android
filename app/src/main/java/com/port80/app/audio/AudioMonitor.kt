package com.port80.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Snapshot of the live audio input, shown on the stream screen. */
data class AudioStatus(
    /** True while audio buffers are flowing through the processor. */
    val active: Boolean = false,
    /** Output level sent to the stream (peak, dBFS, with meter decay). */
    val levelDb: Float = -90f,
    /** No meaningful signal for several seconds (cable, mixer off, fader down). */
    val silent: Boolean = false,
    /** The mixer is hitting digital full scale (input too hot). */
    val clipping: Boolean = false,
    /** Number of detected audio gaps (lost samples) in this session. */
    val cutCount: Int = 0,
    val lastCutAtMs: Long = 0L,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val usbDeviceName: String? = null,
    val rawSource: Boolean = false,
    /** Sample rate really delivered by the input, measured over time (0 = not yet). */
    val measuredSampleRate: Int = 0,
    /**
     * Standard rate the input really runs at when it differs from the configured
     * one (e.g. mixer delivers 44.1 kHz while 48 kHz was requested). 0 = no mismatch.
     */
    val suggestedSampleRate: Int = 0,
    /** Audio / video packets discarded because the network could not keep up. */
    val netDroppedAudio: Long = 0L,
    val netDroppedVideo: Long = 0L,
)

/** Process-wide holder of [AudioStatus], written by the audio thread. */
object AudioMonitor {
    private val _status = MutableStateFlow(AudioStatus())
    val status: StateFlow<AudioStatus> = _status.asStateFlow()

    /** Called once when a new stream session starts. */
    fun resetSession() {
        _status.value = AudioStatus()
    }

    fun setInputInfo(sampleRate: Int, channels: Int, usbDeviceName: String?, rawSource: Boolean) {
        _status.update {
            it.copy(
                sampleRate = sampleRate,
                channels = channels,
                usbDeviceName = usbDeviceName,
                rawSource = rawSource
            )
        }
    }

    internal fun publishLevels(levelDb: Float, silent: Boolean, clipping: Boolean) {
        _status.update { it.copy(active = true, levelDb = levelDb, silent = silent, clipping = clipping) }
    }

    internal fun setMeasuredRate(measured: Int, suggested: Int) {
        _status.update { it.copy(measuredSampleRate = measured, suggestedSampleRate = suggested) }
    }

    fun setNetworkDrops(audio: Long, video: Long) {
        _status.update { it.copy(netDroppedAudio = audio, netDroppedVideo = video) }
    }

    internal fun addCut(nowMs: Long) {
        _status.update { it.copy(cutCount = it.cutCount + 1, lastCutAtMs = nowMs) }
    }

    fun markInactive() {
        _status.update { it.copy(active = false, silent = false, clipping = false, levelDb = -90f) }
    }
}
