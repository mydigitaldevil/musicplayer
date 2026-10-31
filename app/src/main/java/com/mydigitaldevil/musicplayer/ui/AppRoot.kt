package com.mydigitaldevil.musicplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import com.mydigitaldevil.musicplayer.ui.ambient.AmbientScreen
import com.mydigitaldevil.musicplayer.ui.karaoke.KaraokeScreen
import com.mydigitaldevil.musicplayer.ui.library.LibraryScreen
import com.mydigitaldevil.musicplayer.ui.micfx.MicFxScreen
import com.mydigitaldevil.musicplayer.ui.onboarding.OnboardingScreen
import com.mydigitaldevil.musicplayer.ui.player.MiniPlayer
import com.mydigitaldevil.musicplayer.ui.player.NowPlayingScreen
import com.mydigitaldevil.musicplayer.ui.player.VideoPlayerScreen
import com.mydigitaldevil.musicplayer.ui.settings.SettingsScreen
import com.mydigitaldevil.musicplayer.ui.stats.StatsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val NOW_PLAYING = "now_playing"
    const val VIDEO = "video"
    const val MIC_FX = "mic_fx"
    const val KARAOKE = "karaoke"
    const val AMBIENT = "ambient"
    const val STATS = "stats"
    const val SETTINGS = "settings"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.LIBRARY, R.string.tab_library, Icons.Filled.LibraryMusic),
    Tab(Routes.MIC_FX, R.string.tab_mic_fx, Icons.Filled.Mic),
    Tab(Routes.AMBIENT, R.string.tab_ambient, Icons.Filled.Spa),
    Tab(Routes.STATS, R.string.tab_stats, Icons.Filled.Insights),
)

@Composable
fun AppRoot(
    startRoute: String,
    player: PlayerConnection,
    inPip: Boolean,
    externalRoute: String?,
    onExternalRouteHandled: () -> Unit,
    onOnboardingFinished: () -> Unit,
    onOpenSoundboard: () -> Unit,
    onEnterPip: () -> Unit,
    onVideoScreenActive: (Boolean) -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val nowPlaying by player.nowPlaying.collectAsStateWithLifecycle()
    val position by remember { player.positionTicks(1_000) }.collectAsStateWithLifecycle(0L)

    // Deep links from notifications, the QS tile, and files opened from other apps.
    LaunchedEffect(externalRoute) {
        val target = externalRoute ?: return@LaunchedEffect
        // On a cold start `route` is still null; fall back to the start route so onboarding isn't skipped.
        if ((route ?: startRoute) != Routes.ONBOARDING) nav.navigate(target) { launchSingleTop = true }
        onExternalRouteHandled()
    }

    val openPlayer = { nav.navigate(if (nowPlaying.isVideo) Routes.VIDEO else Routes.NOW_PLAYING) { launchSingleTop = true } }

    Scaffold(
        contentWindowInsets = WindowInsets(0), // each screen handles its own insets
        bottomBar = {
            if (!inPip && TABS.any { it.route == route }) {
                Column {
                    if (nowPlaying.hasMedia) {
                        MiniPlayer(
                            nowPlaying = nowPlaying,
                            positionMs = position,
                            onClick = openPlayer,
                            onPlayPause = player::togglePlayPause,
                            onNext = player::next,
                        )
                    }
                    NavigationBar {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.navigateToTab(tab.route) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    nav.navigate(Routes.LIBRARY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } },
                    onOpenVideo = { nav.navigate(Routes.VIDEO) { launchSingleTop = true } },
                    onOpenKaraoke = { nav.navigate(Routes.KARAOKE) { launchSingleTop = true } },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onOpenSoundboard = onOpenSoundboard,
                )
            }
            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(
                    onBack = { nav.popBackStack() },
                    onOpenKaraoke = { nav.navigate(Routes.KARAOKE) { launchSingleTop = true } },
                )
            }
            composable(Routes.VIDEO) {
                VideoPlayerScreen(
                    inPip = inPip,
                    onBack = { nav.popBackStack() },
                    onEnterPip = onEnterPip,
                    onActiveChanged = onVideoScreenActive,
                )
            }
            composable(Routes.MIC_FX) { MicFxScreen(onOpenSoundboard = onOpenSoundboard) }
            composable(Routes.KARAOKE) {
                KaraokeScreen(
                    onBack = { nav.popBackStack() },
                    onPickSong = { nav.navigateToTab(Routes.LIBRARY) },
                )
            }
            composable(Routes.AMBIENT) { AmbientScreen() }
            composable(Routes.STATS) { StatsScreen(onExit = { nav.navigateToTab(Routes.LIBRARY) }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSoundboard = onOpenSoundboard,
                    onReplayOnboarding = { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                )
            }
        }
    }
}

/** Standard bottom-nav behaviour: one back stack entry per tab, state saved and restored. */
private fun NavHostController.navigateToTab(route: String) = navigate(route) {
    popUpTo(Routes.LIBRARY) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
