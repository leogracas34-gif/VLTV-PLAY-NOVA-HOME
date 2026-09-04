package com.vltv.play.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.vltv.play.R

// ────────────────────────────────────────────────────────────────
// Serviço em foreground exigido pelo Android (8+) para manter downloads
// rodando em segundo plano com uma notificação visível. Sem isso, o
// sistema mataria o download assim que o app fosse pra background.
//
// CORREÇÃO: DownloadNotificationHelper fica no pacote
// androidx.media3.exoplayer.offline, não em androidx.media3.ui (erro
// de import da versão anterior deste arquivo).
// ────────────────────────────────────────────────────────────────
@UnstableApi
class VltvDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0
) {
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "vltv_download_channel"
    }

    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager =
        VltvDownloadTracker.getDownloadManager(this)

    override fun getScheduler(): Scheduler =
        PlatformScheduler(this, 1)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }
}
