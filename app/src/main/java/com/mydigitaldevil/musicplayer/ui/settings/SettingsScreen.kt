package com.mydigitaldevil.musicplayer.ui.settings

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.BuildConfig
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.ConsentManager
import com.mydigitaldevil.musicplayer.core.AppPreferences
import com.mydigitaldevil.musicplayer.core.AppSettings
import com.mydigitaldevil.musicplayer.stats.StatsDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val statsDao: StatsDao,
    val consent: ConsentManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefs.setDynamicColor(enabled) }
    fun clearStats() = viewModelScope.launch { statsDao.clear() }
    fun replayOnboarding() = viewModelScope.launch { prefs.setOnboardingDone(false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSoundboard: () -> Unit,
    onReplayOnboarding: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val activity = LocalActivity.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_summary)) },
                    trailingContent = { Switch(checked = settings.dynamicColor, onCheckedChange = viewModel::setDynamicColor) },
                )
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenSoundboard),
                headlineContent = { Text(stringResource(R.string.soundboard_title)) },
                supportingContent = { Text(stringResource(R.string.settings_soundboard_summary)) },
            )
            ListItem(
                modifier = Modifier.clickable { viewModel.replayOnboarding(); onReplayOnboarding() },
                headlineContent = { Text(stringResource(R.string.settings_replay_onboarding)) },
            )
            HorizontalDivider()
            // Required by AdMob/UMP wherever a consent message applies (e.g. EEA/UK).
            if (viewModel.consent.isPrivacyOptionsRequired) {
                ListItem(
                    modifier = Modifier.clickable { activity?.let { viewModel.consent.showPrivacyOptions(it) } },
                    headlineContent = { Text(stringResource(R.string.settings_privacy_options)) },
                    supportingContent = { Text(stringResource(R.string.settings_privacy_options_summary)) },
                )
            }
            ListItem(
                modifier = Modifier.clickable { confirmClear = true },
                headlineContent = { Text(stringResource(R.string.settings_clear_stats)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_stats_summary)) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { showLicences = true },
                headlineContent = { Text(stringResource(R.string.settings_licences)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_stats)) },
            text = { Text(stringResource(R.string.settings_clear_stats_confirm)) },
            confirmButton = { TextButton(onClick = { viewModel.clearStats(); confirmClear = false }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text(stringResource(R.string.settings_licences)) },
            text = {
                Text(stringResource(R.string.licences_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { showLicences = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
