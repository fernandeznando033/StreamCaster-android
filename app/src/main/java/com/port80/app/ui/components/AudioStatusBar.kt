package com.port80.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.port80.app.audio.AudioStatus
import kotlin.math.roundToInt

/** Recent dropouts (within this window) are highlighted on screen. */
private const val RECENT_CUT_MS = 30_000L

/**
 * Compact audio monitor: level meter, input info and warnings
 * (no audio, mixer clipping, audio dropouts).
 */
@Composable
fun AudioStatusBar(
    status: AudioStatus,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    val recentCut = status.cutCount > 0 && nowMs - status.lastCutAtMs < RECENT_CUT_MS
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelMeter(status.levelDb, Modifier.width(140.dp).height(8.dp))
            Text(
                text = if (status.active) "  ${status.levelDb.roundToInt()} dB" else "  sin datos",
                color = Color.White,
                fontSize = 12.sp
            )
        }
        Text(
            text = inputDescription(status),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp
        )
        if (status.silent) Warning("SIN AUDIO: revisar cable USB y mesa")
        if (status.clipping) Warning("MESA SATURANDO: bajar el MAIN")
        if (status.suggestedSampleRate > 0) {
            Warning(
                "La mesa entrega ${formatKhz(status.measuredSampleRate)} kHz: detener e iniciar de nuevo"
            )
        } else if (status.cutCount > 0) {
            Warning(
                text = "Cortes de audio en el celular: ${status.cutCount}",
                color = if (recentCut) Color(0xFFE53935) else Color(0xFF8D6E00)
            )
        }
        val netDrops = status.netDroppedAudio + status.netDroppedVideo
        if (netDrops > 0) {
            Warning(
                text = "Pérdidas por red: audio ${status.netDroppedAudio}, video ${status.netDroppedVideo}",
                color = Color(0xFF8D6E00)
            )
        }
    }
}

@Composable
private fun LevelMeter(levelDb: Float, modifier: Modifier) {
    // Map -60..0 dBFS to 0..1
    val fraction = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        levelDb > -3f -> Color(0xFFE53935)
        levelDb > -12f -> Color(0xFFFFC107)
        else -> Color(0xFF4CAF50)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color)
        )
    }
}

@Composable
private fun Warning(text: String, color: Color = Color(0xFFE53935)) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun inputDescription(s: AudioStatus): String {
    if (s.sampleRate == 0) return "Audio: esperando"
    val source = s.usbDeviceName?.let { "USB ($it)" } ?: "Micrófono interno"
    val khz = formatKhz(s.sampleRate)
    val ch = if (s.channels == 2) "estéreo" else "mono"
    val raw = if (s.rawSource) " · sin procesar" else ""
    return "$source · $khz kHz · $ch$raw"
}

private fun formatKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}"
    else String.format(java.util.Locale.US, "%.1f", hz / 1000f).replace('.', ',')
