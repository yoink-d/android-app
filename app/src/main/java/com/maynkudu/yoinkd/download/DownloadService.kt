package com.maynkudu.yoinkd.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.maynkudu.yoinkd.MainActivity
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.YoinkApplication
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as YoinkApplication).container
    private val owner = UUID.randomUUID().toString()
    private var idleObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION, notification(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yoinkd:downloads")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            container.jobs.jobs.collect { jobs ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(jobs))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        idleObserver?.cancel()
        idleObserver = scope.launch {
            if (intent?.action == PAUSE_ALL) {
                container.downloads.pauseAll()
                stopSelf(startId)
            } else {
                container.downloads.start(owner)
                container.downloads.idle.collect { if (it) stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        container.downloads.serviceStopped(owner)
        stopSelf(startId)
    }

    override fun onDestroy() {
        container.downloads.serviceStopped(owner)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(jobs: List<DownloadJob>): android.app.Notification {
        val active = jobs.filter { it.phase.occupiesSlot || it.phase == DownloadPhase.QUEUED }
        val first = active.firstOrNull { it.phase != DownloadPhase.QUEUED } ?: active.firstOrNull()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.rune_download)
            .setContentTitle(first?.title ?: "yoink'd")
            .setContentText(first?.phase?.label ?: "Starting downloads")
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((first?.progress ?: 0f) * 100).toInt(), first?.progress == null)
            .addAction(R.drawable.rune_pause, "Pause all", pause)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 1
        private const val PAUSE_ALL = "com.maynkudu.yoinkd.PAUSE_ALL"
        fun requestStart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
