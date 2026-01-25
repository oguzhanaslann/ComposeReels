package com.oguzhanaslann.composereels

import android.content.Context
import android.net.Uri
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
import kotlin.math.abs

private const val TAG = "PlayerPool"

@UnstableApi
class PlayerPool(
    private val context: Context,
    val poolSize: Int = 3,
    private val cacheSizeMb: Long = 100L
) {
    private val databaseProvider = StandaloneDatabaseProvider(context)

    private val cache: SimpleCache by lazy {
        File(context.cacheDir, "media_cache").apply { mkdirs() }.let { cacheDir ->
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024 * 1024),
                databaseProvider
            )
        }
    }

    private val upstreamFactory = OkHttpDataSource.Factory(
        OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    )

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val downloadManager: DownloadManager by lazy {
        File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }.let { downloadDir ->
            DownloadManager(
                context,
                databaseProvider,
                cache,
                upstreamFactory
            ) { it.run() }
                .apply {
                    maxParallelDownloads = 3
                    resumeDownloads()
                }
        }
    }

    private val downloadListeners = mutableMapOf<String, DownloadManager.Listener>()
    private val _playersInUse = mutableMapOf<Int, ExoPlayer>()
    val playersInUse = _playersInUse.toMap()
    private val idlePlayers = ArrayDeque<ExoPlayer>()

    init {
        repeat(poolSize) {
            idlePlayers.add(createPlayer())
        }
    }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f
                playWhenReady = false
            }
    }

    @Synchronized
    fun acquirePlayer(page: Int, url: String): ExoPlayer {
        return _playersInUse.getOrPut(page) {
            val player = idlePlayers.removeFirstOrNull() ?: run {
                releasePlayerFurthestFrom(page)
                idlePlayers.removeFirstOrNull() ?: createPlayer()
            }

            player.apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }.also {
                Log.d(TAG, "Pool state: ${_playersInUse.size} in use, ${idlePlayers.size} idle")
            }
        }
    }

    @Synchronized
    fun preloadPage(page: Int, url: String) {
        if (_playersInUse.containsKey(page) || idlePlayers.isEmpty()) return

        idlePlayers.removeFirst().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
            _playersInUse[page] = this
        }
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
        if (isFullyDownloaded(url)) {
            Log.d(TAG, "Already downloaded: $url")
            onComplete?.invoke()
            return
        }

        val existingDownload = downloadManager.downloadIndex.getDownload(url)
        if (existingDownload?.isActiveOrQueued == true) {
            Log.d(TAG, "Download in progress: $url (${existingDownload.percentDownloaded}%)")
            addDownloadListener(url, onProgress, onComplete, onError)
            return
        }

        if (existingDownload?.isStoppedOrFailed == true) {
            Log.d(TAG, "Resuming download: $url (${existingDownload.percentDownloaded}%)")
        }

        addDownloadListener(url, onProgress, onComplete, onError)
        downloadManager.addDownload(
            DownloadRequest.Builder(url, Uri.parse(url)).build()
        )

        Log.d(TAG, "Download started. Active: ${downloadManager.currentDownloads.size}")
    }

    private fun addDownloadListener(
        url: String,
        onProgress: ((Float) -> Unit)?,
        onComplete: (() -> Unit)?,
        onError: ((Exception) -> Unit)?
    ) {
        downloadListeners[url] = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                if (download.request.id != url) return

                Log.d(
                    TAG,
                    "Download $url: ${download.state.stateName} (${download.percentDownloaded}%)"
                )

                when (download.state) {
                    Download.STATE_DOWNLOADING -> {
                        onProgress?.invoke(download.percentDownloaded / 100f)
                    }

                    Download.STATE_COMPLETED -> {
                        Log.d(TAG, "Completed: $url (${download.bytesDownloaded} bytes)")
                        onComplete?.invoke()
                        removeDownloadListener(url)
                    }

                    Download.STATE_FAILED -> {
                        Log.e(TAG, "Failed: $url", finalException)
                        onError?.invoke(finalException ?: Exception("Download failed"))
                        removeDownloadListener(url)
                    }

                    else -> Unit
                }
            }
        }.also { downloadManager.addListener(it) }
    }

    private fun removeDownloadListener(url: String) {
        downloadListeners.remove(url)?.let(downloadManager::removeListener)
    }

    /**
     * Checks if a URL is fully downloaded
     * @param url The video URL to check
     * @return true if fully downloaded, false otherwise
     */
    fun isFullyDownloaded(url: String): Boolean {
        return downloadManager.downloadIndex.getDownload(url)?.state == Download.STATE_COMPLETED
    }

    /**
     * Gets the download progress for a URL
     * @param url The video URL to check
     * @return Download object containing state, progress, and size info, or null if not found
     */
    fun getDownloadInfo(url: String): DownloadInfo? {
        return downloadManager.downloadIndex.getDownload(url)?.let {
            DownloadInfo(
                state = it.state.toDownloadState(),
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
        _playersInUse.keys.maxByOrNull { abs(it - currentPage) }?.let(::releasePage)
    }

    @Synchronized
    fun releasePage(page: Int) {
        _playersInUse.remove(page)?.let { player ->
            player.apply {
                playWhenReady = false
                stop()
                clearMediaItems()
            }
            idlePlayers.add(player)
        }
    }

    fun getPlayerForPage(page: Int): Player? = _playersInUse[page]

    fun releaseAll() {
        downloadListeners.values.forEach(downloadManager::removeListener)
        downloadListeners.clear()

        (_playersInUse.values + idlePlayers).forEach { it.release() }
        _playersInUse.clear()
        idlePlayers.clear()

        runCatching { cache.release() }.onFailure { it.printStackTrace() }
    }
}

// Extension properties for cleaner state checks
private val Download.isActiveOrQueued: Boolean
    get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED

private val Download.isStoppedOrFailed: Boolean
    get() = state == Download.STATE_STOPPED || state == Download.STATE_FAILED

private val Int.stateName: String
    get() = when (this) {
        Download.STATE_QUEUED -> "QUEUED"
        Download.STATE_STOPPED -> "STOPPED"
        Download.STATE_DOWNLOADING -> "DOWNLOADING"
        Download.STATE_COMPLETED -> "COMPLETED"
        Download.STATE_FAILED -> "FAILED"
        Download.STATE_REMOVING -> "REMOVING"
        Download.STATE_RESTARTING -> "RESTARTING"
        else -> "UNKNOWN($this)"
    }

private fun Int.toDownloadState(): DownloadState = when (this) {
    Download.STATE_QUEUED -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    Download.STATE_REMOVING -> DownloadState.REMOVING
    Download.STATE_STOPPED -> DownloadState.STOPPED
    else -> DownloadState.UNKNOWN
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