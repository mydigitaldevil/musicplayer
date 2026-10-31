package com.mydigitaldevil.musicplayer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.library.formatDuration
import com.mydigitaldevil.musicplayer.playback.SleepTimerState
import kotlinx.coroutines.delay

private val DURATIONS_MIN = listOf(5, 15, 30, 45, 60, 90)
private val FADES_SEC = listOf(0, 30, 60, 180, 300)

/** Smart sleep timer: duration, fade-out length, and optionally closing the app at the end. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    onStart: (durationMs: Long, fadeMs: Long, closeApp: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var fadeSec by rememberSaveable { mutableLongStateOf(60L) }
    var closeApp by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sleep_timer_title), style = MaterialTheme.typography.titleLarge)

            if (state.active) {
                val remaining by produceState(state.remainingMs(), state) {
                    while (true) { value = state.remainingMs(SystemClock.elapsedRealtime()); delay(1_000) }
                }
                Text(
                    stringResource(R.string.sleep_timer_remaining, formatDuration(remaining)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sleep_timer_cancel))
                }
                return@Column
            }

            Text(stringResource(R.string.sleep_timer_stop_after), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DURATIONS_MIN.forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.minutes_short, m)) })
                }
            }
            Text(stringResource(R.string.sleep_timer_fade), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FADES_SEC.forEach { s ->
                    val label = when {
                        s == 0 -> stringResource(R.string.sleep_timer_fade_off)
                        s < 60 -> stringResource(R.string.seconds_short, s)
                        else -> stringResource(R.string.minutes_short, s / 60)
                    }
                    FilterChip(selected = fadeSec == s.toLong(), onClick = { fadeSec = s.toLong() }, label = { Text(label) })
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.sleep_timer_close_app)) },
                supportingContent = { Text(stringResource(R.string.sleep_timer_close_app_summary)) },
                trailingContent = { Switch(checked = closeApp, onCheckedChange = { closeApp = it }) },
            )
            Button(
                onClick = { onStart(minutes * 60_000L, fadeSec * 1_000L, closeApp); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sleep_timer_start)) }
        }
    }
}
