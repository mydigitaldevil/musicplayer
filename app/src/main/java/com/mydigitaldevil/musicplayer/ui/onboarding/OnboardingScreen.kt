package com.mydigitaldevil.musicplayer.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydigitaldevil.musicplayer.R
import kotlinx.coroutines.launch

private data class Page(val title: Int, val body: Int, val art: @Composable () -> Unit)

/** Three-page first-run walkthrough: PiP gesture, mic FX with headphones, ambient + sleep timer. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pages = listOf(
        Page(R.string.onboarding_pip_title, R.string.onboarding_pip_body) { PipArt() },
        Page(R.string.onboarding_mic_title, R.string.onboarding_mic_body) { HeadphonesArt() },
        Page(R.string.onboarding_ambient_title, R.string.onboarding_ambient_body) { AmbientArt() },
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            if (!last) TextButton(onClick = onFinished) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) { page.art() }
                Spacer(Modifier.size(32.dp))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Button(
            onClick = { if (last) onFinished() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next)) }
    }
}

/** A phone whose video shrinks into a corner window, like the home gesture does. */
@Composable
private fun PipArt() {
    val t by rememberInfiniteTransition(label = "pip").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "shrink",
    )
    val frame = MaterialTheme.colorScheme.outline
    val video = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(200.dp)) {
        val phone = Size(size.width * 0.55f, size.height * 0.95f)
        val origin = Offset((size.width - phone.width) / 2, (size.height - phone.height) / 2)
        drawRoundRect(frame, origin, phone, CornerRadius(28f), style = Stroke(6f))
        val full = Size(phone.width - 24f, phone.width * 9 / 16)
        val small = Size(full.width * 0.45f, full.height * 0.45f)
        val w = full.width + (small.width - full.width) * t
        val h = full.height + (small.height - full.height) * t
        val start = Offset(origin.x + 12f, origin.y + 40f)
        val end = Offset(origin.x + phone.width - small.width - 16f, origin.y + phone.height - small.height - 40f)
        drawRoundRect(video, Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t), Size(w, h), CornerRadius(12f))
    }
}

/** Headphones with sound rings: monitoring needs headphones (wired is lowest delay). */
@Composable
private fun HeadphonesArt() {
    val transition = rememberInfiniteTransition(label = "rings")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val ring = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            listOf(0f, 0.5f).forEach { offset ->
                val p = (pulse + offset) % 1f
                drawCircle(ring.copy(alpha = 1f - p), radius = size.minDimension * (0.3f + 0.2f * p), style = Stroke(4f))
            }
        }
        Icon(Icons.Filled.Headphones, null, Modifier.size(72.dp).scale(1f + 0.05f * pulse), tint = ring)
    }
}

/** Mixer sliders moving under a moon: ambient sounds plus a sleep timer that fades out. */
@Composable
private fun AmbientArt() {
    val t by rememberInfiniteTransition(label = "mixer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sliders",
    )
    val track = MaterialTheme.colorScheme.outlineVariant
    val knob = MaterialTheme.colorScheme.primary
    val moon = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(200.dp)) {
        drawCircle(moon, radius = size.minDimension * 0.12f, center = Offset(size.width * 0.75f, size.height * 0.2f))
        val levels = listOf(0.3f + 0.5f * t, 0.8f - 0.4f * t, 0.5f + 0.3f * t)
        levels.forEachIndexed { i, level ->
            val y = size.height * (0.5f + i * 0.17f)
            drawLine(track, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), strokeWidth = 8f)
            drawCircle(knob, radius = 14f, center = Offset(size.width * (0.1f + 0.8f * level), y))
        }
    }
}
