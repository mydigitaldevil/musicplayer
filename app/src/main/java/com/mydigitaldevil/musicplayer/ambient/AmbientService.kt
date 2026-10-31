package com.mydigitaldevil.musicplayer.ambient

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
import com.mydigitaldevil.musicplayer.playback.SleepTimer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the ambient mix playing with the screen off or the app in the background. */
@AndroidEntryPoint
class AmbientService : LifecycleService() {

    @Inject lateinit var engine: AmbientEngine
    @Inject lateinit var sleepTimer: SleepTimer

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        engine.start()
        if (!started) {
            started = true
            lifecycleScope.launch { sleepTimer.volume.collect { engine.master = it } }
            lifecycleScope.launch { sleepTimer.finished.collect { stopSelf() } }
            // drop(1): ignore the current value; react only when playback actually stops later.
            lifecycleScope.launch { engine.playing.drop(1).collect { if (!it) stopSelf() } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        engine.master = 1f
        super.onDestroy()
    }

    private fun buildNotification() = run {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_ambient), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_AMBIENT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AmbientService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ambient_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ambient"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_STOP = "com.mydigitaldevil.musicplayer.AMBIENT_STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, AmbientService::class.java))
        fun stop(context: Context) {
            context.startService(Intent(context, AmbientService::class.java).setAction(ACTION_STOP))
        }
    }
}
