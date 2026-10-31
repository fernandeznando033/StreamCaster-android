package com.port80.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.pedro.library.base.Camera2Base
import com.port80.app.util.RedactingLogger

/**
 * Prepares the audio side of a stream:
 * - detects a USB mixer and uses a sample rate it really supports,
 * - asks for the UNPROCESSED source when available (no OEM filters),
 * - installs [LiveAudioProcessor] (EQ, compressor, limiter, monitoring).
 */
object AudioPipeline {
    private const val TAG = "AudioPipeline"

    fun prepare(
        camera: Camera2Base,
        bitrateBps: Int,
        requestedSampleRate: Int,
        stereo: Boolean,
        context: Context?,
    ): Boolean {
        val fx = AudioFxStore.settings.value
        val usb = context?.let { findUsbInput(it) }
        val sampleRate = pickSampleRate(usb, requestedSampleRate)
        val channels = if (stereo) 2 else 1

        val wantRaw = fx.rawUsbInput && usb != null && context != null && supportsUnprocessed(context)
        camera.setCustomAudioEffect(LiveAudioProcessor(sampleRate, channels))

        var usedRaw = false
        var ok = false
        if (wantRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ok = try {
                camera.prepareAudio(
                    MediaRecorder.AudioSource.UNPROCESSED, bitrateBps, sampleRate, stereo, false, false
                )
            } catch (e: Exception) {
                RedactingLogger.e(TAG, "UNPROCESSED source failed", e)
                false
            }
            usedRaw = ok
        }
        if (!ok) {
            ok = camera.prepareAudio(
                MediaRecorder.AudioSource.DEFAULT, bitrateBps, sampleRate, stereo, false, false
            )
        }

        val name = usb?.productName?.toString()?.takeIf { it.isNotBlank() } ?: usb?.let { "USB" }
        AudioMonitor.setInputInfo(sampleRate, channels, name, usedRaw)
        RedactingLogger.d(
            TAG,
            "Audio prepared ok=$ok rate=$sampleRate ch=$channels usb=${usb != null} raw=$usedRaw"
        )
        return ok
    }

    private fun findUsbInput(context: Context): AudioDeviceInfo? = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                it.type == TYPE_USB_HEADSET
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Keep the user's rate if the mixer supports it (or reports nothing);
     * otherwise prefer 48 kHz, then 44.1 kHz.
     */
    private fun pickSampleRate(usb: AudioDeviceInfo?, requested: Int): Int {
        val rates = usb?.sampleRates ?: return requested
        if (rates.isEmpty() || requested in rates) return requested
        return when {
            48_000 in rates -> 48_000
            44_100 in rates -> 44_100
            else -> requested
        }
    }

    private fun supportsUnprocessed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        } catch (e: Exception) {
            false
        }
    }

    /** AudioDeviceInfo.TYPE_USB_HEADSET (API 26), as a literal for minSdk 23. */
    private const val TYPE_USB_HEADSET = 22
}
