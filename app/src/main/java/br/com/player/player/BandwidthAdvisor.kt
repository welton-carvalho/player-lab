package br.com.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter


@OptIn(UnstableApi::class)
class BandwidthAdvisor(private val bandwidthMeter: BandwidthMeter) {

    // Returns a BufferConfig tuned to measured bandwidth. Uses `base` as ceiling:
    // values are only lowered for slow networks, never raised beyond what the
    // caller configured (e.g. ReelsBufferConfig's 800ms fast-start is preserved).
    fun recommendedBufferConfig(base: BufferConfig): BufferConfig {
        val bitsPerSecond = bandwidthMeter.bitrateEstimate
        return when {
            bitsPerSecond <= 0L || bitsPerSecond < 500_000L -> base.copy(
                bufferForPlaybackMs = minOf(base.bufferForPlaybackMs, 1_000),
                bufferForPlaybackAfterRebufferMs = minOf(base.bufferForPlaybackAfterRebufferMs, 2_000),
                maxBufferMs = minOf(base.maxBufferMs, 15_000)
            )
            bitsPerSecond < 2_000_000L -> base.copy(
                bufferForPlaybackMs = minOf(base.bufferForPlaybackMs, 1_500),
                bufferForPlaybackAfterRebufferMs = minOf(base.bufferForPlaybackAfterRebufferMs, 3_000),
                maxBufferMs = minOf(base.maxBufferMs, 25_000)
            )
            else -> base
        }
    }
}
