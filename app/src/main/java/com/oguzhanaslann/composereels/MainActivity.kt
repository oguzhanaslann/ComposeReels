package com.oguzhanaslann.composereels

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
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
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.oguzhanaslann.composereels.ui.theme.ComposeReelsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ComposeReelsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoPager(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPager(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val videoUrls = remember {
        listOf(
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
        )
    }

    val mediaItems = remember(videoUrls) {
        videoUrls.map { MediaItem.fromUri(it) }
    }

    val preloadHolder = remember(mediaItems) {
        createPreloadManagerAndPlayer(context, mediaItems)
    }

    val pagerState = rememberPagerState { videoUrls.size }

    SyncPagerWithPreloadManager(
        pagerState = pagerState,
        preloadHolder = preloadHolder,
        mediaItems = mediaItems
    )

    DisposableEffect(Unit) {
        onDispose {
            preloadHolder.release()
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val isCurrentPage = pagerState.settledPage == page
        if (isCurrentPage) {
            VideoPlayer(
                player = preloadHolder.player,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@UnstableApi
@Composable
private fun SyncPagerWithPreloadManager(
    pagerState: PagerState,
    preloadHolder: PreloadHolder,
    mediaItems: List<MediaItem>
) {
    LaunchedEffect(pagerState, preloadHolder) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                val mediaItem = mediaItems[settledPage]

                preloadHolder.setCurrentPlayingIndex(settledPage)

                val mediaSource = preloadHolder.preloadManager.getMediaSource(mediaItem)
                if (mediaSource != null) {
                    preloadHolder.player.setMediaSource(mediaSource)
                } else {
                    preloadHolder.player.setMediaItem(mediaItem)
                }
                preloadHolder.player.prepare()
                preloadHolder.player.playWhenReady = true
            }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> preloadHolder.player.playWhenReady = true
                Lifecycle.Event.ON_PAUSE -> preloadHolder.player.playWhenReady = false
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@UnstableApi
private fun createPreloadManagerAndPlayer(
    context: Context,
    mediaItems: List<MediaItem>,
    cacheSizeMb: Long = 100L
): PreloadHolder {
    val databaseProvider = StandaloneDatabaseProvider(context)

    val cacheDir = File(context.cacheDir, "media_cache")
    if (!cacheDir.exists()) cacheDir.mkdirs()
    val cache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024 * 1024),
        databaseProvider
    )

    val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)

    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

    val targetPreloadStatusControl = ReelsTargetPreloadStatusControl()

    val preloadManagerBuilder = DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
        .setMediaSourceFactory(mediaSourceFactory)

    val preloadManager = preloadManagerBuilder.build()

    val preloadManagerListener = object : PreloadManagerListener {
        override fun onCompleted(mediaItem: MediaItem) {
            Log.d("PreloadAnalytics", "Preload completed for: ${mediaItem.mediaId.ifEmpty { mediaItem.localConfiguration?.uri }}")
        }

        override fun onError(preloadException: PreloadException) {
            Log.e("PreloadAnalytics", "Preload error", preloadException)
        }
    }
    preloadManager.addListener(preloadManagerListener)

    val player = preloadManagerBuilder.buildExoPlayer().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 1f
        playWhenReady = false
    }

    mediaItems.forEachIndexed { index, mediaItem ->
        preloadManager.add(mediaItem, index)
    }
    targetPreloadStatusControl.currentPlayingIndex = 0
    preloadManager.setCurrentPlayingIndex(0)
    preloadManager.invalidate()

    return PreloadHolder(player, preloadManager, targetPreloadStatusControl, cache)
}

@UnstableApi
class ReelsTargetPreloadStatusControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    var currentPlayingIndex: Int = C.INDEX_UNSET

    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
        if (currentPlayingIndex == C.INDEX_UNSET) {
            return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }

        val distance = rankingData - currentPlayingIndex

        return when {
            distance == 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(5000L)
            distance == -1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000L)
            abs(distance) == 2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            abs(distance) <= 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }
}

@UnstableApi
class PreloadHolder(
    val player: ExoPlayer,
    val preloadManager: DefaultPreloadManager,
    private val targetPreloadStatusControl: ReelsTargetPreloadStatusControl,
    private val cache: SimpleCache
) {
    fun setCurrentPlayingIndex(index: Int) {
        targetPreloadStatusControl.currentPlayingIndex = index
        preloadManager.setCurrentPlayingIndex(index)
        preloadManager.invalidate()
    }

    fun release() {
        preloadManager.release()
        player.release()
        try {
            cache.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
