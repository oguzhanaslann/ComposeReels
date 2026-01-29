package com.oguzhanaslann.composereels

import android.Manifest
import android.app.Notification
import androidx.annotation.RequiresPermission
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

@UnstableApi
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    0
) {
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "video_downloads"
    }

    override fun getDownloadManager(): DownloadManager {
        val pool = PlayerPool(this)
        return pool.downloadManager
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return DownloadNotificationHelper(
            applicationContext,
            DOWNLOAD_NOTIFICATION_CHANNEL_ID
        ).buildProgressNotification(
            applicationContext,
            R.drawable.ic_download,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }
}