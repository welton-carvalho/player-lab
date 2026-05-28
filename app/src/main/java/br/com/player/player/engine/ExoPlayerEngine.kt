package br.com.player.player.engine

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import br.com.player.player.AdaptivePreloadStatusControl
import br.com.player.player.BandwidthAdvisor
import br.com.player.player.BufferConfig
import br.com.player.player.CacheConfig
import br.com.player.player.DeviceCapabilityTier
import br.com.player.player.DeviceCapabilityTier.maxPreloadDistance
import br.com.player.player.MediaItemConfig
import br.com.player.player.MediaSourceBuilder
import br.com.player.player.NetworkQualityProvider
import br.com.player.player.PreloadPolicy
import br.com.player.player.toMediaItem

@OptIn(UnstableApi::class)
class ExoPlayerEngine(
    private val app: Application,
    bufferConfig: BufferConfig = BufferConfig(),
    cacheConfig: CacheConfig = CacheConfig()
) : PlayerEngine {

    private var eventListener: PlayerEventListener? = null

    private val networkProvider = NetworkQualityProvider(app)

    // Singleton bandwidth meter shared with the underlying ExoPlayer instance so that
    // all HTTP transfers (preload + playback) contribute to the same bandwidth estimate.
    private val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(app)
    private val bandwidthAdvisor = BandwidthAdvisor(bandwidthMeter)

    private val preloadControl = AdaptivePreloadStatusControl().also { ctrl ->
        ctrl.deviceMaxDistance = DeviceCapabilityTier.assess(app).maxPreloadDistance()
        ctrl.policy = networkProvider.currentTier().toPolicy()
    }

    private val tunedBufferConfig = bandwidthAdvisor.recommendedBufferConfig(bufferConfig)

    private val preloadBuilder = DefaultPreloadManager.Builder(app, preloadControl)
        .apply {
            setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        tunedBufferConfig.minBufferMs,
                        tunedBufferConfig.maxBufferMs,
                        tunedBufferConfig.bufferForPlaybackMs,
                        tunedBufferConfig.bufferForPlaybackAfterRebufferMs
                    )
                    .build()
            )
            // Injeta o factory com cache de disco para que bytes preloadados sejam
            // gravados no mesmo LRU de 200 MB usado pelo player ativo. Sem isso,
            // o preload baixa os dados mas os descarta; o player re-baixa tudo ao tocar.
            setMediaSourceFactory(MediaSourceBuilder.createFactory(app, cacheConfig))
            // bandwidthMeter is the process-wide singleton so all ExoPlayer instances
            // (including the one built by preloadBuilder.buildExoPlayer()) share estimates
            // automatically — no explicit setBandwidthMeter() call needed here.
        }

    private val preloadManager: DefaultPreloadManager = preloadBuilder.build().also { manager ->
        manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                // mediaId was set to the item index (as string) in registerForPreload()
                val index = mediaItem.mediaId.toIntOrNull() ?: return
                preloadControl.forcedIndices = preloadControl.forcedIndices - index
                eventListener?.onPreloadCompleted(index)
            }
        })
    }

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
        // Playlist-internal preload: o ExoPlayer mantém os próximos itens da
        // playlist prontos com até 5 s bufferizados, dando ao caminho
        // `seekToItem` (usado em forcePlaylistMode) a sensação de continuidade
        // instantânea entre vídeos. Não afeta o ramo DefaultPreloadManager.
        exoPlayer.setPreloadConfiguration(
            ExoPlayer.PreloadConfiguration(/* targetPreloadDurationUs = */ 5_000_000L)
        )
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
        preloadControl.forcedIndices = emptySet()
        items.forEachIndexed { index, item ->
            // mediaId = índice → cada posição é um MediaItem distinto no preload manager,
            // mesmo quando várias posições compartilham a mesma URL.
            preloadManager.add(item.toMediaItem(mediaId = index.toString()), index)
        }
        preloadManager.invalidate()
    }

    override fun playPreloadedItemAt(index: Int, config: MediaItemConfig, cacheConfig: CacheConfig) {
        // Mesmo mediaId usado no registro (índice) para casar a busca no preload manager.
        val mediaItem = config.toMediaItem(mediaId = index.toString())
        val source = preloadManager.getMediaSource(mediaItem)
            ?: MediaSourceBuilder.build(app, config, cacheConfig)
        exoPlayer.setMediaSource(source)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    override fun setCurrentPreloadIndex(index: Int) {
        preloadControl.currentPlayingIndex = index
        // Refresh policy on each item change — cheap OS call, executed right before
        // invalidate() recalculates preload targets for all registered items.
        preloadControl.policy = networkProvider.currentTier().toPolicy()
        preloadManager.setCurrentPlayingIndex(index)
    }

    override fun invalidatePreload() = preloadManager.invalidate()
    override fun resetPreload() = preloadManager.reset()

    override fun requestPreloadAt(index: Int) {
        preloadControl.forcedIndices = preloadControl.forcedIndices + index
        preloadManager.invalidate()
    }

    override fun release() {
        try {
            exoPlayer.removeListener(internalListener)
            preloadManager.release()
            exoPlayer.release()
        } catch (_: Exception) {}
    }

    private fun NetworkQualityProvider.NetworkTier.toPolicy(): PreloadPolicy = when (this) {
        NetworkQualityProvider.NetworkTier.WIFI          -> PreloadPolicy.WIFI
        NetworkQualityProvider.NetworkTier.CELLULAR_FAST -> PreloadPolicy.CELLULAR_FAST
        NetworkQualityProvider.NetworkTier.CELLULAR_SLOW -> PreloadPolicy.CELLULAR_SLOW
        NetworkQualityProvider.NetworkTier.OFFLINE       -> PreloadPolicy.OFFLINE
    }
}
