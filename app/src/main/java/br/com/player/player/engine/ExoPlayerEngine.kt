package br.com.player.player.engine

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import br.com.player.player.BufferConfig
import br.com.player.player.CacheConfig
import br.com.player.player.MediaItemConfig
import br.com.player.player.MediaSourceBuilder
import br.com.player.player.toMediaItem
import kotlin.math.abs

@OptIn(UnstableApi::class)
class ExoPlayerEngine(
    private val app: Application,
    bufferConfig: BufferConfig = BufferConfig()
) : PlayerEngine {

    private var eventListener: PlayerEventListener? = null

    private var currentPreloadIndex: Int = 0

    private inner class PlaylistPreloadStatusControl :
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

        override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
            return when (abs(rankingData - currentPreloadIndex)) {
                1    -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
                2    -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                3, 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private val preloadBuilder = DefaultPreloadManager.Builder(app, PlaylistPreloadStatusControl())
        .apply {
            setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferConfig.minBufferMs,
                        bufferConfig.maxBufferMs,
                        bufferConfig.bufferForPlaybackMs,
                        bufferConfig.bufferForPlaybackAfterRebufferMs
                    ).build()
            )
        }

    private val preloadManager: DefaultPreloadManager = preloadBuilder.build()
    private val exoPlayer: ExoPlayer = preloadBuilder.buildExoPlayer()

    private val internalListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            eventListener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            eventListener?.onPlaybackStateChanged(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isEnded = playbackState == Player.STATE_ENDED
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val duration = exoPlayer.duration.takeIf { it != Long.MIN_VALUE } ?: 0L
            eventListener?.onPositionChanged(newPosition.positionMs, duration)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            eventListener?.onMediaItemIndexChanged(exoPlayer.currentMediaItemIndex)
        }
    }

    init {
        exoPlayer.addListener(internalListener)
    }

    override val player: Player get() = exoPlayer

    override fun setEventListener(listener: PlayerEventListener) { eventListener = listener }
    override fun clearEventListener() { eventListener = null }

    override val isPlaying: Boolean get() = exoPlayer.isPlaying
    override val currentPosition: Long get() = exoPlayer.currentPosition
    override val duration: Long get() = exoPlayer.duration
    override val currentMediaItemIndex: Int get() = exoPlayer.currentMediaItemIndex

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) = exoPlayer.seekTo(index, positionMs)
    override fun seekToNext() = exoPlayer.seekToNext()
    override fun seekToPrevious() = exoPlayer.seekToPrevious()

    override fun loadDirect(items: List<MediaItemConfig>, cacheConfig: CacheConfig) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        preloadManager.reset()
        items.forEach { item ->
            exoPlayer.addMediaSource(MediaSourceBuilder.build(app, item, cacheConfig))
        }
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    override fun registerForPreload(items: List<MediaItemConfig>) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        preloadManager.reset()
        items.forEachIndexed { index, item ->
            preloadManager.add(item.toMediaItem(), index)
        }
        preloadManager.invalidate()
    }

    override fun playPreloadedItemAt(index: Int, config: MediaItemConfig, cacheConfig: CacheConfig) {
        val mediaItem = config.toMediaItem()
        val source = preloadManager.getMediaSource(mediaItem)
            ?: MediaSourceBuilder.build(app, config, cacheConfig)
        exoPlayer.setMediaSource(source)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    override fun setCurrentPreloadIndex(index: Int) {
        currentPreloadIndex = index
        preloadManager.setCurrentPlayingIndex(index)
    }

    override fun invalidatePreload() = preloadManager.invalidate()
    override fun resetPreload() = preloadManager.reset()

    override fun release() {
        try {
            exoPlayer.removeListener(internalListener)
            preloadManager.release()
            exoPlayer.release()
        } catch (_: Exception) {}
    }
}
