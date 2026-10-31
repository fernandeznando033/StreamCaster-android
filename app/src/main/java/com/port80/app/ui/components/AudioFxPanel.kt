package com.port80.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.port80.app.audio.AudioFxSettings
import com.port80.app.audio.AudioFxStore
import com.port80.app.audio.CompressorPreset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Controls for the live audio processing (5-band EQ, compressor, final volume,
 * limiter). Changes apply to the stream immediately.
 */
@Composable
fun AudioFxPanel(modifier: Modifier = Modifier) {
    val fx by AudioFxStore.settings.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        SwitchRow(
            title = "Ecualizador (5 bandas)",
            subtitle = "Se aplica al audio que sale en la transmisión",
            checked = fx.eqEnabled,
            onChange = { v -> AudioFxStore.update { it.copy(eqEnabled = v) } }
        )
        AudioFxSettings.BAND_LABELS.forEachIndexed { band, label ->
            DbSlider(
                label = "$label (${formatHz(AudioFxSettings.BAND_FREQS_HZ[band])})",
                value = fx.eqGainsDb.getOrElse(band) { 0f },
                range = AudioFxSettings.MIN_GAIN_DB..AudioFxSettings.MAX_GAIN_DB,
                enabled = fx.eqEnabled,
                onChange = { AudioFxStore.setBandGain(band, it) }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Compresor", style = MaterialTheme.typography.titleSmall)
        Text(
            "Empareja el volumen: baja los picos fuertes y sube lo suave",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompressorPreset.entries.forEach { preset ->
                val selected = fx.compressorPreset == preset.ordinal
                val onClick = { AudioFxStore.update { it.copy(compressorPreset = preset.ordinal) } }
                if (selected) {
                    Button(
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(preset.label, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(preset.label, maxLines = 1) }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        DbSlider(
            label = "Volumen final",
            value = fx.outputGainDb,
            range = -12f..12f,
            enabled = true,
            onChange = { v -> AudioFxStore.update { it.copy(outputGainDb = v) } }
        )
        SwitchRow(
            title = "Limitador de seguridad (-1 dB)",
            subtitle = "Evita que el audio distorsione en los picos",
            checked = fx.limiterEnabled,
            onChange = { v -> AudioFxStore.update { it.copy(limiterEnabled = v) } }
        )
        SwitchRow(
            title = "Entrada USB sin procesar",
            subtitle = "Pide a Android el audio crudo de la mesa. Probar primero apagado. Se aplica al iniciar la transmisión.",
            checked = fx.rawUsbInput,
            onChange = { v -> AudioFxStore.update { it.copy(rawUsbInput = v) } }
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { AudioFxStore.resetProcessing() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restablecer (ecualizador plano, compresor apagado)")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DbSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    val sign = if (value > 0f) "+" else ""
    ValueSlider(
        label = label,
        valueText = "$sign${formatNumber(value)} dB",
        value = value,
        range = range,
        step = 0.5f,
        enabled = enabled,
        onChange = onChange
    )
}

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v -> onChange((v / step).roundToInt() * step) },
            valueRange = range,
            steps = steps,
            enabled = enabled
        )
    }
}

private fun formatNumber(v: Float): String {
    val rounded = (v * 10f).roundToInt() / 10f
    val text = if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
    return text.replace('.', ',')
}

private fun formatHz(hz: Float): String =
    if (hz >= 1000f) "${formatNumber(hz / 1000f)} kHz" else "${hz.roundToInt()} Hz"
