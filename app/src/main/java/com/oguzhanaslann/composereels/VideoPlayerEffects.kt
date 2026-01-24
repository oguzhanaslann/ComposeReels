package com.oguzhanaslann.composereels

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerEffects(
    videoUrls: List<String>,
    pagerState: PagerState,
    playerPool: PlayerPool,
) {
    val maxPlayerCount = playerPool.poolSize

    LaunchedEffect(Unit) {
        preloadByIndex(videoUrls, 0, playerPool)
        if (videoUrls.size > 1) {
            preloadByIndex(videoUrls, 1, playerPool)
        }
    }

    // Handle page changes and preloading
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                releaseDistantPages(page, playerPool)
                preloadPreviousPage(page, videoUrls, playerPool)
                acquireCurrentPage(page, videoUrls, playerPool)
                preloadNextPages(page, videoUrls, playerPool, maxPlayerCount)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerPool.releaseAll()
        }
    }
}

private fun preloadPreviousPage(
    page: Int,
    videoUrls: List<String>,
    playerPool: PlayerPool
) {
    val previousPage = page - 1
    if (isIndexValid(previousPage, videoUrls)) {
        preloadByIndex(videoUrls, previousPage, playerPool)
    }
}

private fun acquireCurrentPage(
    page: Int,
    videoUrls: List<String>,
    playerPool: PlayerPool
) {
    if (isIndexValid(page, videoUrls)) {
        val url = videoUrls[page]
        playerPool.acquirePlayer(page, url)
    }
}

private fun preloadByIndex(
    videoUrls: List<String>,
    targetIndex: Int,
    playerPool: PlayerPool
) {
    if (isIndexValid(targetIndex, videoUrls)) {
        val url = videoUrls[targetIndex]
        playerPool.preloadPage(targetIndex, url)
    }
}

private fun preloadNextPages(
    page: Int,
    videoUrls: List<String>,
    playerPool: PlayerPool,
    maxPlayerCount: Int
) {
    repeat(maxPlayerCount - 2) {
        val nextIndex = it + 1 + page
        if (isIndexValid(nextIndex, videoUrls)) {
            preloadByIndex(videoUrls, nextIndex, playerPool)
        }
    }
}

private fun releaseDistantPages(currentPage: Int, playerPool: PlayerPool) {
    val pagesToKeep = setOf(currentPage - 1, currentPage, currentPage + 1)

    val pagesToRelease = playerPool.playersInUse.keys.filter { page ->
        page !in pagesToKeep
    }

    pagesToRelease.forEach {
        playerPool.releasePage(it)
    }
}

private fun isIndexValid(index: Int, videoUrls: List<String>): Boolean {
    return index in videoUrls.indices
}