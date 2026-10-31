package com.mydigitaldevil.musicplayer.soundboard.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.soundboard.SoundPad

/**
 * In-app soundboard. Tap a pad to play it; long-press to pin it to quick pads (notification +
 * Quick Settings tile) or remove a recording pad. Deliberately ad-free: it's used mid-game or
 * mid-call, where any ad would be an interruption and an accidental-click risk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundboardSheet(viewModel: SoundboardViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(SoundboardIntent.SetQuickPads(enabled = true, permissionGranted = granted))
    }

    LaunchedEffect(Unit) { viewModel.onIntent(SoundboardIntent.RefreshRecordings) }
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(msg.textRes()), Toast.LENGTH_SHORT).show()
        viewModel.onIntent(SoundboardIntent.MessageShown)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidth {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.soundboard_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.onIntent(SoundboardIntent.StopAll) }) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.soundboard_stop_all))
                    }
                }
            }

            items(state.pads, key = { it.id }) { pad ->
                PadTile(
                    pad = pad,
                    pinned = pad.id in state.pinnedIds,
                    onPlay = { viewModel.onIntent(SoundboardIntent.Play(pad)) },
                    onTogglePin = { viewModel.onIntent(SoundboardIntent.TogglePin(pad)) },
                    onRemove = { viewModel.onIntent(SoundboardIntent.Remove(pad)) },
                )
            }

            fullWidth {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.quick_pads_setting)) },
                    supportingContent = { Text(stringResource(R.string.quick_pads_setting_summary)) },
                    trailingContent = {
                        Switch(
                            checked = state.quickPadsEnabled,
                            onCheckedChange = { on ->
                                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.onIntent(SoundboardIntent.SetQuickPads(on))
                                }
                            },
                        )
                    },
                )
            }

            if (state.recordings.isNotEmpty()) {
                fullWidth {
                    Text(
                        stringResource(R.string.soundboard_add_recordings),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.recordings, key = { it.path }, span = { GridItemSpan(maxLineSpan) }) { file ->
                    ListItem(
                        headlineContent = { Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.onIntent(SoundboardIntent.AddRecording(file)) }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.soundboard_add_as_pad))
                            }
                        },
                    )
                }
            }

            fullWidth { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadTile(
    pad: SoundPad,
    pinned: Boolean,
    onPlay: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (pinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onPlay,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.soundboard_pad_options),
                ),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.soundboard_pinned),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    pad.label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.soundboard_unpin else R.string.soundboard_pin)) },
                onClick = { menuOpen = false; onTogglePin() },
            )
            if (!pad.isPreset) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.soundboard_remove)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun SoundboardMessage.textRes(): Int = when (this) {
    SoundboardMessage.PIN_LIMIT -> R.string.soundboard_msg_pin_limit
    SoundboardMessage.NEED_PIN_FIRST -> R.string.soundboard_msg_pin_first
    SoundboardMessage.RECORDING_TOO_LONG -> R.string.soundboard_msg_too_long
    SoundboardMessage.RECORDING_UNREADABLE -> R.string.soundboard_msg_unreadable
    SoundboardMessage.NOTIFICATIONS_BLOCKED -> R.string.soundboard_msg_notifications_blocked
}
