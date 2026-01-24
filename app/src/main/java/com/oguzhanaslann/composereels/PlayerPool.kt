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
}