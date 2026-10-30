package com.mydigitaldevil.musicplayer.ads.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock

/**
 * The opt-in step required before every rewarded ad: says exactly what the user gets and for
 * how long, and makes declining as easy as accepting. Renders nothing if no ad is ready, so the
 * user is never promised a reward that cannot be delivered.
 */
@Composable
fun RewardedOfferDialog(
    adManager: AdManager,
    unlock: RewardedUnlock,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ready by adManager.rewardedReady.collectAsStateWithLifecycle()
    if (!ready) return

    val (title, body) = when (unlock) {
        RewardedUnlock.UNLIMITED_KARAOKE -> R.string.reward_karaoke_title to R.string.reward_karaoke_body
        RewardedUnlock.PRO_VISUALIZERS -> R.string.reward_visualizers_title to R.string.reward_visualizers_body
        RewardedUnlock.EXCLUSIVE_MIC_FILTERS -> R.string.reward_filters_title to R.string.reward_filters_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onWatch) { Text(stringResource(R.string.reward_watch)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reward_not_now)) } },
    )
}
