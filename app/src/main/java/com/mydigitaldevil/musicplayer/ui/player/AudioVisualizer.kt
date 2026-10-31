package com.mydigitaldevil.musicplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.audio.SpectrumAnalyzer
import com.mydigitaldevil.musicplayer.core.VisualizerStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Audio-reactive visualizer fed by [SpectrumAnalyzer]. Only this Canvas redraws at frame rate;
 * the rest of the screen doesn't recompose.
 */
@Composable
fun AudioVisualizer(
    analyzer: SpectrumAnalyzer,
    style: VisualizerStyle,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(analyzer) {
        analyzer.acquire()
        onDispose { analyzer.release() }
    }
    val bands by analyzer.bands.collectAsStateWithLifecycle()
    val wave by analyzer.waveform.collectAsStateWithLifecycle()
    Canvas(modifier) {
        when (style) {
            VisualizerStyle.BARS -> drawBars(bands, primary, secondary)
            VisualizerStyle.WAVE -> drawWave(wave, primary, secondary)
            VisualizerStyle.NEON_RING -> drawNeonRing(bands, primary, secondary)
        }
    }
}

private fun DrawScope.drawBars(bands: FloatArray, primary: Color, secondary: Color) {
    if (bands.isEmpty()) return
    val slot = size.width / bands.size
    val barWidth = slot * 0.62f
    val brush = Brush.verticalGradient(listOf(secondary, primary), startY = 0f, endY = size.height)
    bands.forEachIndexed { i, v ->
        val h = (v * size.height).coerceAtLeast(barWidth / 2)
        drawRoundRect(
            brush = brush,
            topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
        )
    }
}

private fun DrawScope.drawWave(wave: FloatArray, primary: Color, secondary: Color) {
    if (wave.size < 2) return
    val mid = size.height / 2
    val step = size.width / (wave.size - 1)
    val path = Path().apply {
        moveTo(0f, mid + wave[0] * mid * 0.9f)
        for (i in 1 until wave.size) lineTo(i * step, mid + wave[i] * mid * 0.9f)
    }
    val brush = Brush.horizontalGradient(listOf(primary, secondary, primary))
    // Wide translucent stroke under a thin bright one reads as a neon glow.
    drawPath(path, brush, alpha = 0.25f, style = Stroke(width = 14f, cap = StrokeCap.Round))
    drawPath(path, brush, style = Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawNeonRing(bands: FloatArray, primary: Color, secondary: Color) {
    if (bands.isEmpty()) return
    val center = Offset(size.width / 2, size.height / 2)
    val base = min(size.width, size.height) * 0.28f
    val maxSpike = min(size.width, size.height) * 0.2f
    val avg = bands.average().toFloat()
    val ringRadius = base * (1f + avg * 0.15f)
    listOf(18f to 0.15f, 8f to 0.35f, 3f to 1f).forEach { (w, a) ->
        drawCircle(primary, radius = ringRadius, center = center, alpha = a, style = Stroke(w), blendMode = BlendMode.Plus)
    }
    // Mirror the bands around the circle so the ring is symmetric.
    val spikes = bands.size * 2
    for (i in 0 until spikes) {
        val v = bands[if (i < bands.size) i else spikes - 1 - i]
        val angle = (2 * PI * i / spikes - PI / 2).toFloat()
        val dir = Offset(cos(angle), sin(angle))
        val start = center + dir * ringRadius
        val end = center + dir * (ringRadius + 4f + v * maxSpike)
        drawLine(secondary, start, end, strokeWidth = 10f, alpha = 0.25f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(secondary, start, end, strokeWidth = 4f, cap = StrokeCap.Round)
    }
}
