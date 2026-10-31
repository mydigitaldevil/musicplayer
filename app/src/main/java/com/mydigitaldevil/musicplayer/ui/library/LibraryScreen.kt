package com.mydigitaldevil.musicplayer.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.nativead.NativeAd
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.ui.BannerPlacement
import com.mydigitaldevil.musicplayer.ads.ui.NativeAdCard
import com.mydigitaldevil.musicplayer.ads.ui.SafeBannerAd
import com.mydigitaldevil.musicplayer.library.LocalMedia
import com.mydigitaldevil.musicplayer.library.MediaPermissions
import com.mydigitaldevil.musicplayer.library.MediaThumbnail
import com.mydigitaldevil.musicplayer.library.formatDuration
import com.mydigitaldevil.musicplayer.ui.LocalAdManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNowPlaying: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenKaraoke: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSoundboard: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val adManager = LocalAdManager.current
    val access by viewModel.access.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val nativeAds by viewModel.nativeAds.collectAsStateWithLifecycle()
    val adsReady by adManager.adsReady.collectAsStateWithLifecycle()

    // Re-check on every resume: the user may have changed access in system settings.
    LifecycleResumeEffect(Unit) {
        viewModel.onAccessChanged(MediaPermissions.access(context))
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onAccessChanged(MediaPermissions.access(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onOpenSoundboard) {
                        Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.soundboard_open))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val currentAccess = access
            when {
                currentAccess == null -> Spacer(Modifier.weight(1f))
                !currentAccess.any -> PermissionCard(
                    modifier = Modifier.weight(1f),
                    onGrant = { permissionLauncher.launch(MediaPermissions.requested) },
                )
                else -> {
                    SearchField(query, viewModel::onQueryChanged)
                    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                        Tab(selected = tab == LibraryTab.SONGS, onClick = { viewModel.onTabSelected(LibraryTab.SONGS) },
                            text = { Text(stringResource(R.string.library_tab_songs, songs.size)) })
                        Tab(selected = tab == LibraryTab.VIDEOS, onClick = { viewModel.onTabSelected(LibraryTab.VIDEOS) },
                            text = { Text(stringResource(R.string.library_tab_videos, videos.size)) })
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            LibraryTab.SONGS -> MediaList(
                                media = songs,
                                emptyText = stringResource(if (currentAccess.audio) R.string.library_empty_songs else R.string.library_no_audio_access),
                                nativeAds = nativeAds,
                                adsReady = adsReady,
                                onRequestAd = viewModel::requestNativeAd,
                                onClick = { index -> viewModel.play(songs, index); onOpenNowPlaying() },
                                onKaraoke = { index -> viewModel.play(songs, index); onOpenKaraoke() },
                            )
                            LibraryTab.VIDEOS -> Column {
                                if (currentAccess.videoPartial) PartialVideoAccessBanner { permissionLauncher.launch(MediaPermissions.requested) }
                                MediaList(
                                    media = videos,
                                    emptyText = stringResource(R.string.library_empty_videos),
                                    nativeAds = emptyMap(), // no ads between video thumbnails: they look too similar
                                    adsReady = false,
                                    onRequestAd = {},
                                    onClick = { index -> viewModel.play(videos, index); onOpenVideo() },
                                    onKaraoke = null,
                                )
                            }
                        }
                    }
                }
            }
            SafeBannerAd(adManager, BannerPlacement.BROWSE)
            // Keeps the banner clear of the mini player / navigation bar below it.
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.library_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear)) }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

private sealed interface Row {
    data class Media(val index: Int) : Row
    data class Ad(val slot: Int) : Row
}

@Composable
private fun MediaList(
    media: List<LocalMedia>,
    emptyText: String,
    nativeAds: Map<Int, NativeAd>,
    adsReady: Boolean,
    onRequestAd: (Int) -> Unit,
    onClick: (Int) -> Unit,
    onKaraoke: ((Int) -> Unit)?,
) {
    if (media.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val rows = remember(media.size, adsReady) {
        buildList {
            var slot = 0
            media.indices.forEach { i ->
                add(Row.Media(i))
                val n = i + 1
                val isAdPosition = n == LibraryViewModel.FIRST_AD_AFTER ||
                    (n > LibraryViewModel.FIRST_AD_AFTER && (n - LibraryViewModel.FIRST_AD_AFTER) % LibraryViewModel.AD_EVERY == 0)
                if (adsReady && isAdPosition && slot < LibraryViewModel.MAX_AD_SLOTS && n < media.size) add(Row.Ad(slot++))
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        items(rows, key = { row -> when (row) { is Row.Media -> media[row.index].mediaId; is Row.Ad -> "ad-${row.slot}" } }) { row ->
            when (row) {
                is Row.Media -> MediaRow(media[row.index], { onClick(row.index) }, onKaraoke?.let { k -> { k(row.index) } })
                is Row.Ad -> {
                    LaunchedEffect(row.slot) { onRequestAd(row.slot) }
                    nativeAds[row.slot]?.let { NativeAdCard(it) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(media: LocalMedia, onClick: () -> Unit, onKaraoke: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    val supporting = buildString {
        media.artist?.let { append(it).append(" · ") }
        append(formatDuration(media.durationMs))
        if (media.height > 0) append(" · ").append(media.height).append('p')
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { MediaThumbnail(media, size = 52.dp) },
        headlineContent = { Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (onKaraoke == null) null else {
            {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_sing_karaoke)) },
                            onClick = { menuOpen = false; onKaraoke() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun PermissionCard(modifier: Modifier, onGrant: () -> Unit) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.library_permission_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.library_permission_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onGrant) { Text(stringResource(R.string.library_permission_grant)) }
            }
        }
    }
}

@Composable
private fun PartialVideoAccessBanner(onAllowAll: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.library_partial_videos)) },
        trailingContent = { TextButton(onClick = onAllowAll) { Text(stringResource(R.string.library_partial_videos_action)) } },
    )
}
