package com.mydigitaldevil.musicplayer.ui.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.ads.NaturalBreak
import com.mydigitaldevil.musicplayer.ads.ui.NativeAdCard
import com.mydigitaldevil.musicplayer.ui.LocalAdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(onExit: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val activity = LocalActivity.current
    val adManager = LocalAdManager.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showWrapped by remember { mutableStateOf(false) }

    // Explicitly backing out of stats is one of the few natural breaks for an interstitial.
    BackHandler {
        val act = activity
        if (act != null) adManager.showInterstitialAtNaturalBreak(act, NaturalBreak.BACK_FROM_STATS, onExit) else onExit()
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(selected = state.period == p, onClick = { viewModel.setPeriod(p) }, label = { Text(stringResource(p.labelRes())) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.stats_minutes), state.minutes.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stats_plays), state.summary.plays.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.stats_skip_rate), percent(state.skipRate), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stats_repeat_ratio), percent(state.repeatRatio), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stats_completion), percent(state.completionRate), Modifier.weight(1f))
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.stats_peak_hours), style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.peakHour?.let { stringResource(R.string.stats_peak_hour_value, hourLabel(it)) } ?: stringResource(R.string.stats_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HourChart(state.hours, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest, Modifier.fillMaxWidth().height(96.dp).padding(top = 12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            listOf(0, 6, 12, 18).forEach { h ->
                                Text(hourLabel(h), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = { showWrapped = true }, modifier = Modifier.fillMaxWidth(), enabled = state.summary.plays > 0) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text(stringResource(R.string.stats_create_wrapped), Modifier.padding(start = 8.dp))
                }
            }
            item { Text(stringResource(R.string.stats_top_songs), style = MaterialTheme.typography.titleMedium) }
            if (state.topSongs.isEmpty()) {
                item { Text(stringResource(R.string.stats_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(state.topSongs, key = { _, it -> it.mediaId }) { i, song ->
                ListItem(
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { song.artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = { Text(stringResource(R.string.stats_plays_count, song.plays)) },
                )
                // One native ad, as its own card after the 3rd row: never inside a song row.
                if (i == 2) NativeAdCard(adManager)
            }
            if (state.topArtists.isNotEmpty()) {
                item { Text(stringResource(R.string.stats_top_artists), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(state.topArtists, key = { _, it -> "artist-${it.artist}" }) { i, artist ->
                    ListItem(
                        leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                        headlineContent = { Text(artist.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(stringResource(R.string.stats_minutes_count, artist.listenedMs / 60_000)) },
                    )
                }
            }
        }
    }

    if (showWrapped) WrappedDialog(state, adManager) { showWrapped = false }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HourChart(hours: IntArray, bar: Color, track: Color, modifier: Modifier) {
    Canvas(modifier) {
        val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
        val slot = size.width / 24
        val w = slot * 0.6f
        hours.forEachIndexed { h, plays ->
            val x = h * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            val bh = size.height * plays / max
            if (bh > 0) drawRoundRect(bar, Offset(x, size.height - bh), Size(w, bh), CornerRadius(w / 2))
        }
    }
}

/** Shareable "Wrapped" infographic, rendered to a PNG through a Compose graphics layer. */
@Composable
private fun WrappedDialog(state: StatsUiState, adManager: AdManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val today = LocalDate.now()
    val title = when (state.period) {
        StatsPeriod.YEAR -> stringResource(R.string.wrapped_year, today.year)
        StatsPeriod.MONTH -> stringResource(R.string.wrapped_month, today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), today.year)
        StatsPeriod.WEEK -> stringResource(R.string.wrapped_week)
        StatsPeriod.ALL_TIME -> stringResource(R.string.wrapped_all_time)
    }
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    val onCard = MaterialTheme.colorScheme.onPrimary

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    shareImage(context, adManager, bitmap)
                }
            }) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 14f)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                    .clip(MaterialTheme.shapes.large)
                    .background(gradient)
                    .padding(20.dp),
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, color = onCard, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.wrapped_minutes, state.minutes), color = onCard, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.wrapped_top_songs), color = onCard.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                    state.topSongs.take(5).forEachIndexed { i, s ->
                        Text("${i + 1}. ${s.title}", color = onCard, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    }
                    state.topArtists.firstOrNull()?.let {
                        Text(stringResource(R.string.wrapped_top_artist, it.artist), color = onCard, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    state.peakHour?.let {
                        Text(stringResource(R.string.wrapped_peak_hour, hourLabel(it)), color = onCard, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.app_name), color = onCard.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    )
}

private suspend fun shareImage(context: Context, adManager: AdManager, bitmap: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "share").apply { mkdirs() }.let { dir ->
            File(dir, "wrapped.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    adManager.suppressNextAppOpen()
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun percent(v: Float) = "${(v * 100).toInt()}%"

private fun hourLabel(h: Int): String = when {
    h == 0 -> "12 AM"
    h < 12 -> "$h AM"
    h == 12 -> "12 PM"
    else -> "${h - 12} PM"
}

private fun StatsPeriod.labelRes(): Int = when (this) {
    StatsPeriod.WEEK -> R.string.stats_period_week
    StatsPeriod.MONTH -> R.string.stats_period_month
    StatsPeriod.YEAR -> R.string.stats_period_year
    StatsPeriod.ALL_TIME -> R.string.stats_period_all
}
