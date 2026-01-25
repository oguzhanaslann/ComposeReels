package com.oguzhanaslann.composereels

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs

private const val TAG = "PlayerPool"

@UnstableApi
class PlayerPool(
    private val context: Context,
    val poolSize: Int = 3,
    private val cacheSizeMb: Long = 100L
) {
    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    private val cache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "media_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024 * 1024),
            databaseProvider
        )
    }

    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val mediaSourceFactory: DefaultMediaSourceFactory by lazy {
        DefaultMediaSourceFactory(cacheDataSourceFactory)
    }

    private val downloadManager: DownloadManager by lazy {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        DownloadManager(
            context,
            databaseProvider,
            cache,
            upstreamFactory,
            Executor { it.run() }
        ).apply {
            maxParallelDownloads = 3
            resumeDownloads()
        }
    }

    private val downloadListeners = mutableMapOf<String, DownloadManager.Listener>()

    private val _playersInUse = mutableMapOf<Int, ExoPlayer>()
    val playersInUse = _playersInUse.toMap()
    private val idlePlayers = mutableListOf<ExoPlayer>()

    init {
        repeat(poolSize) {
            idlePlayers.add(createExoPlayer())
        }
    }

    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f
                playWhenReady = false
            }
    }

    @Synchronized
    fun acquirePlayer(page: Int, url: String): ExoPlayer {
        _playersInUse[page]?.let {
            return it
        }

        if (idlePlayers.isEmpty()) {
            releasePlayerFurthestFrom(page)
        }

        val player = if (idlePlayers.isNotEmpty()) {
            idlePlayers.removeAt(0)
        } else {
            createExoPlayer()
        }

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()

        _playersInUse[page] = player
        Log.d(TAG, "Pool state: ${_playersInUse.size} in use, ${idlePlayers.size} idle")
        return player
    }

    @Synchronized
    fun preloadPage(page: Int, url: String) {
        if (_playersInUse.containsKey(page)) {
            return
        }

        if (idlePlayers.isEmpty()) {
            return
        }

        val player = idlePlayers.removeAt(0)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = false

        _playersInUse[page] = player
    }

    /**
     * Downloads a video URL to cache using DownloadManager
     * @param url The video URL to download
     * @param onProgress Optional callback for download progress (0.0 to 1.0)
     * @param onComplete Optional callback when download completes
     * @param onError Optional callback when download fails
     */
    fun downloadToCache(
        url: String,
        onProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        Log.d(TAG, "Starting download for: $url")

        // Check if already downloaded
        if (isFullyDownloaded(url)) {
            Log.d(TAG, "Already downloaded: $url")
            onComplete?.invoke()
            return
        }

        // Check if already downloading
        val existingDownload = downloadManager.downloadIndex.getDownload(url)
        if (existingDownload != null) {
            when (existingDownload.state) {
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                    Log.d(TAG, "Download already in progress: $url (${existingDownload.percentDownloaded}%)")
                    // Just add listener to existing download
                    addDownloadListener(url, onProgress, onComplete, onError)
                    return
                }
                Download.STATE_STOPPED, Download.STATE_FAILED -> {
                    Log.d(TAG, "Resuming download: $url (was at ${existingDownload.percentDownloaded}%)")
                    // Will resume from where it stopped
                }
            }
        }

        val downloadRequest = DownloadRequest.Builder(url, url.toUri())
            .build()

        addDownloadListener(url, onProgress, onComplete, onError)

        Log.d(TAG, "Adding download request to DownloadManager")
        downloadManager.addDownload(downloadRequest)

        Log.d(TAG, "Current downloads count: ${downloadManager.currentDownloads.size}")
    }

    private fun addDownloadListener(
        url: String,
        onProgress: ((Float) -> Unit)?,
        onComplete: (() -> Unit)?,
        onError: ((Exception) -> Unit)?
    ) {
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                Log.d(TAG, "onDownloadChanged - URL: ${download.request.id}, State: ${getStateName(download.state)}")

                if (download.request.id == url) {
                    when (download.state) {
                        Download.STATE_DOWNLOADING -> {
                            val progress = download.percentDownloaded / 100f
                            onProgress?.invoke(progress)
                            Log.d(TAG, "Downloading: ${download.percentDownloaded}%")
                        }
                        Download.STATE_COMPLETED -> {
                            Log.d(TAG, "Download completed: $url")
                            Log.d(TAG, "Downloaded bytes: ${download.bytesDownloaded}")
                            onComplete?.invoke()
                            removeDownloadListener(url)
                        }
                        Download.STATE_FAILED -> {
                            Log.e(TAG, "Download failed: $url", finalException)
                            onError?.invoke(finalException ?: Exception("Download failed"))
                            removeDownloadListener(url)
                        }
                        Download.STATE_STOPPED -> {
                            Log.d(TAG, "Download stopped: $url at ${download.percentDownloaded}%")
                        }
                        Download.STATE_QUEUED -> {
                            Log.d(TAG, "Download queued: $url")
                        }
                    }
                }
            }
        }

        downloadListeners[url] = listener
        downloadManager.addListener(listener)
    }

    private fun getStateName(state: Int): String {
        return when (state) {
            Download.STATE_QUEUED -> "STATE_QUEUED"
            Download.STATE_STOPPED -> "STATE_STOPPED"
            Download.STATE_DOWNLOADING -> "STATE_DOWNLOADING"
            Download.STATE_COMPLETED -> "STATE_COMPLETED"
            Download.STATE_FAILED -> "STATE_FAILED"
            Download.STATE_REMOVING -> "STATE_REMOVING"
            Download.STATE_RESTARTING -> "STATE_RESTARTING"
            else -> "STATE_UNKNOWN($state)"
        }
    }

    private fun removeDownloadListener(url: String) {
        downloadListeners.remove(url)?.let { listener ->
            downloadManager.removeListener(listener)
        }
    }

    /**
     * Checks if a URL is fully downloaded
     * @param url The video URL to check
     * @return true if fully downloaded, false otherwise
     */
    fun isFullyDownloaded(url: String): Boolean {
        val download = downloadManager.downloadIndex.getDownload(url)
        val isCompleted = download?.state == Download.STATE_COMPLETED
        Log.d(TAG, "isFullyDownloaded - URL: $url, Download: ${download?.state}, Result: $isCompleted")
        return isCompleted
    }

    /**
     * Gets the download progress for a URL
     * @param url The video URL to check
     * @return Download object containing state, progress, and size info, or null if not found
     */
    fun getDownloadInfo(url: String): DownloadInfo? {
        val download = downloadManager.downloadIndex.getDownload(url)
        return download?.let {
            DownloadInfo(
                state = when (it.state) {
                    Download.STATE_QUEUED -> DownloadState.QUEUED
                    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
                    Download.STATE_COMPLETED -> DownloadState.COMPLETED
                    Download.STATE_FAILED -> DownloadState.FAILED
                    Download.STATE_REMOVING -> DownloadState.REMOVING
                    Download.STATE_STOPPED -> DownloadState.STOPPED
                    else -> DownloadState.UNKNOWN
                },
                percentDownloaded = it.percentDownloaded,
                bytesDownloaded = it.bytesDownloaded
            )
        }
    }

    /**
     * Removes a download from cache
     * @param url The video URL to remove
     */
    fun removeDownload(url: String) {
        downloadManager.removeDownload(url)
    }

    /**
     * Removes all downloads
     */
    fun removeAllDownloads() {
        downloadManager.currentDownloads.forEach { download ->
            downloadManager.removeDownload(download.request.id)
        }
    }

    private fun releasePlayerFurthestFrom(currentPage: Int) {
        val furthestPage = _playersInUse.keys.maxByOrNull { abs(it - currentPage) }
        furthestPage?.let { releasePage(it) }
    }

    @Synchronized
    fun releasePage(page: Int) {
        _playersInUse.remove(page)?.let { player ->
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            idlePlayers.add(player)
        }
    }

    fun getPlayerForPage(page: Int): Player? {
        return _playersInUse[page]
    }

    fun releaseAll() {
        downloadListeners.forEach { (_, listener) ->
            downloadManager.removeListener(listener)
        }
        downloadListeners.clear()

        _playersInUse.values.forEach {
            it.playWhenReady = false
            it.release()
        }
        _playersInUse.clear()

        idlePlayers.forEach { it.release() }
        idlePlayers.clear()

        try {
            cache.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun String.toUri() = android.net.Uri.parse(this)
}

data class DownloadInfo(
    val state: DownloadState,
    val percentDownloaded: Float,
    val bytesDownloaded: Long
)

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    REMOVING,
    STOPPED,
    UNKNOWN
}