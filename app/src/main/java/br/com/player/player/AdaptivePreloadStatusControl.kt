package br.com.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

@OptIn(UnstableApi::class)
class AdaptivePreloadStatusControl :
    TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    @Volatile var currentPlayingIndex: Int = 0
    @Volatile var policy: PreloadPolicy = PreloadPolicy.WIFI

    // Set by DeviceCapabilityTier — caps the maximum preload distance on low-RAM devices.
    @Volatile var deviceMaxDistance: Int = Int.MAX_VALUE

    // Indices requested via requestPreloadAt() bypass the distance limit and always get
    // full treatment equivalent to distance 1.
    @Volatile var forcedIndices: Set<Int> = emptySet()

    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
        if (rankingData in forcedIndices) {
            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
        }
        val distance = abs(rankingData - currentPlayingIndex)
        if (distance == 0) return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        val effectiveMax = minOf(policy.maxPreloadDistance, deviceMaxDistance)
        if (distance > effectiveMax) return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        return when (distance) {
            1 -> if (policy.distance1Ms > 0)
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(policy.distance1Ms)
            else
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            2 -> if (policy.distance2Ms > 0)
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(policy.distance2Ms)
            else
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
        }
    }
}

data class PreloadPolicy(
    val distance1Ms: Long,
    val distance2Ms: Long,
    val maxPreloadDistance: Int
) {
    companion object {
        val WIFI = PreloadPolicy(distance1Ms = 5_000L, distance2Ms = 2_000L, maxPreloadDistance = 4)
        val CELLULAR_FAST = PreloadPolicy(distance1Ms = 3_000L, distance2Ms = 0L, maxPreloadDistance = 2)
        val CELLULAR_SLOW = PreloadPolicy(distance1Ms = 0L, distance2Ms = 0L, maxPreloadDistance = 1)
        val OFFLINE = PreloadPolicy(distance1Ms = 0L, distance2Ms = 0L, maxPreloadDistance = 0)
    }
}
