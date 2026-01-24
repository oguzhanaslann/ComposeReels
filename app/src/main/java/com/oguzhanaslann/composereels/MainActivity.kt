package com.oguzhanaslann.composereels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.oguzhanaslann.composereels.ui.theme.ComposeReelsTheme
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ComposeReelsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val context = LocalContext.current
                    val playerPool = remember { PlayerPool(context, poolSize = 4) }

                    DisposableEffect(Unit) {
                        onDispose {
                            playerPool.releaseAll()
                        }
                    }

                    VideoPager(
                        playerPool = playerPool,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPager(
    playerPool: PlayerPool,
    modifier: Modifier = Modifier
) {
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
    val pagerState = rememberPagerState { videoUrls.size }

    VideoPlayerEffects(
        videoUrls = videoUrls,
        pagerState = pagerState,
        playerPool = playerPool
    )

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        var player by remember { mutableStateOf<Player?>(null) }

        LaunchedEffect(page) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settledPage ->
                    if (page == settledPage) {
                        player = playerPool.getPlayerForPage(settledPage)
                        player?.playWhenReady = true
                    } else {
                        // Pause when not current page
                        player?.playWhenReady = false
                    }
                }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(page, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when {
                    pagerState.settledPage != page -> player?.playWhenReady = false
                    event == Lifecycle.Event.ON_RESUME -> player?.playWhenReady = true
                    event == Lifecycle.Event.ON_PAUSE -> player?.playWhenReady = false
                    else -> Unit
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                player?.playWhenReady = false
                playerPool.releasePage(page)
                player = null
            }
        }


        player?.let {
            VideoPlayer(
                player = it,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}