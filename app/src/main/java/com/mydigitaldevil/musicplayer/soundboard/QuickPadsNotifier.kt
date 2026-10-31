package com.mydigitaldevil.musicplayer.soundboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mydigitaldevil.musicplayer.MainActivity
import com.mydigitaldevil.musicplayer.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quick pads: a silent notification whose action buttons play up to three pinned pads.
 *
 * This replaces a floating overlay. It works over games, video and calls without
 * SYSTEM_ALERT_WINDOW, and needs no foreground service: each tap is a broadcast to
 * [QuickPadReceiver]. The only permission involved is POST_NOTIFICATIONS on Android 13+.
 */
@Singleton
class QuickPadsNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SoundboardRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /** Keeps the notification in sync with the pinned pads and the on/off setting. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(repository.quickPadsEnabled, repository.pinnedPads) { enabled, pads -> enabled to pads }
                .distinctUntilChanged()
                .collect { (enabled, pads) -> if (enabled && pads.isNotEmpty()) show(pads) else cancel() }
        }
    }

    fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun show(pads: List<SoundPad>) {
        if (!canPostNotifications()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_quick_pads),
                NotificationManager.IMPORTANCE_LOW, // no sound, no heads-up: it must never interrupt a game
            ).apply { setShowBadge(false) },
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_soundboard)
            .setContentTitle(context.getString(R.string.quick_pads_title))
            .setContentText(pads.joinToString(" · ") { it.label })
            .setContentIntent(openAppIntent())
            .setDeleteIntent(receiverIntent(QuickPadReceiver.ACTION_DISMISSED, REQUEST_DISMISS))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true) // Android 14+ still lets the user swipe it away; that turns quick pads off
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        pads.take(SoundboardCodec.MAX_PINNED).forEachIndexed { index, pad ->
            builder.addAction(0, pad.label, receiverIntent(QuickPadReceiver.ACTION_PLAY, index, pad.id))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun receiverIntent(action: String, requestCode: Int, padId: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode, // distinct per slot, so the three actions don't overwrite each other
            Intent(context, QuickPadReceiver::class.java)
                .setAction(action)
                .apply { padId?.let { putExtra(QuickPadReceiver.EXTRA_PAD_ID, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SOUNDBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "quick_pads"
        const val NOTIFICATION_ID = 3001
        const val REQUEST_DISMISS = 100
    }
}
