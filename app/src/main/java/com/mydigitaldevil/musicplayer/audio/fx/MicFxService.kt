package com.mydigitaldevil.musicplayer.audio.fx

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mydigitaldevil.musicplayer.MainActivity
import com.mydigitaldevil.musicplayer.R
import com.mydigitaldevil.musicplayer.ads.AdManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps live mic monitoring alive while the user switches apps (e.g. to a game).
 *
 * Android 14+: a `microphone` FGS may only be started while the app is visible and
 * RECORD_AUDIO is granted, so [start] must be called from a resumed activity.
 */
@AndroidEntryPoint
class MicFxService : LifecycleService() {

    @Inject lateinit var engine: MicFxEngine
    @Inject lateinit var adManager: AdManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // Never interrupt a live performance with a full-screen ad.
        adManager.setFullScreenSuppressed(SUPPRESSION_KEY, true)

        if (!engine.start()) {
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleScope.launch {
            engine.state.collect { if (!it.running) stopSelf() }
        }
        // A killed microphone service must not silently come back and open the mic.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        adManager.setFullScreenSuppressed(SUPPRESSION_KEY, false)
        super.onDestroy()
    }

    private fun buildNotification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_mic), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicFxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mic_fx_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mic_fx"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_STOP = "com.mydigitaldevil.musicplayer.MIC_FX_STOP"
        private const val SUPPRESSION_KEY = "mic_fx"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MicFxService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MicFxService::class.java).setAction(ACTION_STOP))
        }
    }
}
