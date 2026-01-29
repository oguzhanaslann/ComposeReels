package com.oguzhanaslann.composereels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.oguzhanaslann.composereels.ui.theme.ComposeReelsTheme
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Removed direct call, now handled after permission in setContent
        // downloadVideoInBackground(this , "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
        setContent {
            ComposeReelsTheme {
                val context = LocalContext.current
                val videoUrlToDownload =
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"

                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted: Boolean ->
                    if (isGranted) {
                        Log.d("Permission", "Notification permission granted")
                        downloadVideoInBackground(context, videoUrlToDownload)
                    } else {
                        Log.d("Permission", "Notification permission denied")
                        // Handle the case where permission is denied, maybe show a message
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            downloadVideoInBackground(context, videoUrlToDownload)
                        }
                    } else {
                        downloadVideoInBackground(context, videoUrlToDownload)
                    }
                }
            }
        }
    }


    // Trigger a download through the service
    @OptIn(UnstableApi::class)
    fun downloadVideoInBackground(context: Context, url: String) {
        val downloadRequest = DownloadRequest.Builder(url, Uri.parse(url))
            .build()

        DownloadService.sendAddDownload(
            context,
            VideoDownloadService::class.java,
            downloadRequest,
            true // Set to true to start service in foreground immediately
        )
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
