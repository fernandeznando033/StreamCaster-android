package com.port80.app.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live audio processing settings (5-band EQ, compressor, output gain, limiter).
 *
 * These are applied to the PCM audio right before it is AAC-encoded, so every
 * change is heard in the live stream immediately, without restarting it.
 */
data class AudioFxSettings(
    val eqEnabled: Boolean = true,
    /** Gains in dB for [BAND_FREQS_HZ], range [MIN_GAIN_DB]..[MAX_GAIN_DB]. */
    val eqGainsDb: List<Float> = List(BAND_COUNT) { 0f },
    /** 0 = off, 1 = soft, 2 = medium, 3 = strong. See [CompressorPreset]. */
    val compressorPreset: Int = 0,
    val outputGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    /** Use Android's UNPROCESSED audio source when a USB mixer is connected. */
    val rawUsbInput: Boolean = false,
) {
    companion object {
        const val BAND_COUNT = 5
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        /** Band 0 is a low shelf, band 4 a high shelf, the rest are peaking filters. */
        val BAND_FREQS_HZ = floatArrayOf(80f, 250f, 1000f, 3500f, 10000f)
        val BAND_LABELS = listOf("Graves", "Medio-graves", "Medios", "Presencia", "Agudos")
    }
}

/** Simple compressor presets: threshold (dBFS), ratio and make-up gain (dB). */
enum class CompressorPreset(val label: String, val thresholdDb: Float, val ratio: Float, val makeupDb: Float) {
    OFF("Apagado", 0f, 1f, 0f),
    SOFT("Suave", -16f, 2f, 2f),
    MEDIUM("Medio", -20f, 3f, 4f),
    STRONG("Fuerte", -24f, 4f, 6f);

    companion object {
        fun of(index: Int): CompressorPreset = entries.getOrElse(index) { OFF }
    }
}

/** Process-wide store for [AudioFxSettings], persisted in SharedPreferences. */
object AudioFxStore {
    private const val PREFS_NAME = "audio_fx"

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(AudioFxSettings())
    val settings: StateFlow<AudioFxSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _settings.value = load(p)
    }

    fun update(transform: (AudioFxSettings) -> AudioFxSettings) {
        _settings.update(transform)
        prefs?.let { save(it, _settings.value) }
    }

    fun setBandGain(band: Int, gainDb: Float) {
        if (band !in 0 until AudioFxSettings.BAND_COUNT) return
        val clamped = gainDb.coerceIn(AudioFxSettings.MIN_GAIN_DB, AudioFxSettings.MAX_GAIN_DB)
        update { s -> s.copy(eqGainsDb = s.eqGainsDb.toMutableList().also { it[band] = clamped }) }
    }

    /** Back to defaults (flat EQ, compressor off) but keeps the raw-USB choice. */
    fun resetProcessing() {
        update { s -> AudioFxSettings(rawUsbInput = s.rawUsbInput) }
    }

    private fun load(p: SharedPreferences): AudioFxSettings {
        val d = AudioFxSettings()
        return AudioFxSettings(
            eqEnabled = p.getBoolean("eq_enabled", d.eqEnabled),
            eqGainsDb = List(AudioFxSettings.BAND_COUNT) { i -> p.getFloat("eq_band_$i", 0f) },
            compressorPreset = p.getInt("comp_preset", d.compressorPreset)
                .coerceIn(0, CompressorPreset.entries.size - 1),
            outputGainDb = p.getFloat("output_gain", d.outputGainDb),
            limiterEnabled = p.getBoolean("limiter_enabled", d.limiterEnabled),
            rawUsbInput = p.getBoolean("raw_usb_input_v2", d.rawUsbInput),
        )
    }

    private fun save(p: SharedPreferences, s: AudioFxSettings) {
        p.edit().apply {
            putBoolean("eq_enabled", s.eqEnabled)
            s.eqGainsDb.forEachIndexed { i, g -> putFloat("eq_band_$i", g) }
            putInt("comp_preset", s.compressorPreset)
            putFloat("output_gain", s.outputGainDb)
            putBoolean("limiter_enabled", s.limiterEnabled)
            putBoolean("raw_usb_input_v2", s.rawUsbInput)
        }.apply()
    }
}
