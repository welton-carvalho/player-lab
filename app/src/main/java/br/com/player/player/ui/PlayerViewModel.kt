package br.com.player.player.ui

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import br.com.player.player.PlayerConfig
import br.com.player.player.toMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * MVI contract
 */
sealed class PlayerIntent {
    object TogglePlayPause : PlayerIntent()
    data class SeekTo(val positionMs: Long) : PlayerIntent()
    data class LoadMediaList(val config: PlayerConfig) : PlayerIntent()
    object NextItem : PlayerIntent()
    object PreviousItem : PlayerIntent()
    /** Retenta o último carregamento após um erro. */
    object RetryLast : PlayerIntent()
}

data class PlayerUiState(
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val currentIndex: Int = 0,
    val totalItems: Int = 0
)

sealed class PlayerEffect {
    data class ShowErrorToast(val message: String) : PlayerEffect()
    object OnPlaylistEnded : PlayerEffect()
}

/**
 * ViewModel que gerencia o ExoPlayer com estratégia automática de playback:
 *
 * - **1 item** → ExoPlayer direto: `addMediaSource()` + `prepare()`.
 *   Sem overhead do PreloadManager.
 *
 * - **2+ itens** → `DefaultPreloadManager`: pré-carrega itens vizinhos em background,
 *   eliminando rebuffer ao navegar pela playlist.
 *   Estratégia de preload por distância:
 *     · ±1 → 3 segundos de conteúdo
 *     · ±2 → seleciona trilhas
 *     · ±3-4 → prepara manifest
 *     · >4 → não pré-carrega
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PlayerEffect>()
    val effects = _effects.asSharedFlow()

    // ── Estado interno da playlist ────────────────────────────────────────────
    private var currentPlayingIndex: Int = 0
    private var loadedMediaConfigs: List<MediaItemConfig> = emptyList()
    private var lastConfig: PlayerConfig? = null

    /**
     * true  → modo PreloadManager (lista com 2+ itens)
     * false → modo direto (item único)
     * Definido automaticamente em loadMediaList baseado em mediaList.size.
     */
    private var prefetchEnabled: Boolean = false

    // ── DefaultPreloadManager ─────────────────────────────────────────────────

    private inner class PlaylistPreloadStatusControl :
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

        override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
            val distance = abs(rankingData - currentPlayingIndex)
            return when (distance) {
                1    -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
                2    -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                3, 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private val preloadStatusControl = PlaylistPreloadStatusControl()

    /**
     * Builder compartilhado — OBRIGATÓRIO que ExoPlayer e DefaultPreloadManager
     * sejam criados pelo mesmo builder (compartilham LoadControl, BandwidthMeter,
     * TrackSelector e RenderersFactory).
     */
    private val preloadBuilder: DefaultPreloadManager.Builder by lazy {
        DefaultPreloadManager.Builder(app, preloadStatusControl).apply {
            val bufCfg = lastConfig?.bufferConfig ?: BufferConfig()
            setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufCfg.minBufferMs,
                        bufCfg.maxBufferMs,
                        bufCfg.bufferForPlaybackMs,
                        bufCfg.bufferForPlaybackAfterRebufferMs
                    ).build()
            )
        }
    }

    private val preloadManager: DefaultPreloadManager by lazy { preloadBuilder.build() }
    private val _player: ExoPlayer by lazy { preloadBuilder.buildExoPlayer() }

    // ── Listener do player ───────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(state: Int) {
            _uiState.value = _uiState.value.copy(
                isBuffering = state == Player.STATE_BUFFERING
            )
            if (state == Player.STATE_ENDED) {
                viewModelScope.launch {
                    _effects.emit(PlayerEffect.OnPlaylistEnded)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _uiState.value = _uiState.value.copy(
                currentPositionMs = newPosition.positionMs,
                durationMs = _player.duration.takeIf { it != Long.MIN_VALUE } ?: 0L
            )
        }

        /**
         * No modo direto (1 item ou seekToNext/Previous), o ExoPlayer gerencia
         * a transição internamente. Sincroniza o índice da UI com o estado real do player.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!prefetchEnabled) {
                val newIndex = _player.currentMediaItemIndex
                currentPlayingIndex = newIndex
                _uiState.value = _uiState.value.copy(currentIndex = newIndex)
            }
        }
    }

    init {
        _player.addListener(playerListener)
        startPositionUpdates()
    }

    /** Polling de posição a cada 500ms enquanto o player está reproduzindo. */
    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                if (_player.isPlaying) {
                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = _player.currentPosition,
                        durationMs = _player.duration.coerceAtLeast(0L)
                    )
                }
                delay(500L)
            }
        }
    }

    fun getPlayer(): ExoPlayer = _player

    @OptIn(UnstableApi::class)
    fun handleIntent(intent: PlayerIntent) {
        when (intent) {
            is PlayerIntent.TogglePlayPause -> {
                if (_player.isPlaying) _player.pause() else _player.play()
            }
            is PlayerIntent.SeekTo -> {
                _player.seekTo(intent.positionMs)
            }
            is PlayerIntent.LoadMediaList -> {
                lastConfig = intent.config
                loadMediaList(intent.config)
            }
            PlayerIntent.NextItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex < loadedMediaConfigs.size - 1)
                        playItemAt(currentPlayingIndex + 1)
                } else {
                    _player.seekToNext()
                }
            }
            PlayerIntent.PreviousItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex > 0)
                        playItemAt(currentPlayingIndex - 1)
                } else {
                    _player.seekToPrevious()
                }
            }
            PlayerIntent.RetryLast -> {
                lastConfig?.let { loadMediaList(it) }
            }
        }
    }

    @UnstableApi
    private fun loadMediaList(config: PlayerConfig) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(errorMessage = null)

                // Estratégia automática: 2+ itens = prefetch, 1 item = direto
                prefetchEnabled = config.mediaList.size > 1
                loadedMediaConfigs = config.mediaList

                if (prefetchEnabled) {
                    loadWithPreload(config)
                } else {
                    loadDirect(config)
                }

                _uiState.value = _uiState.value.copy(totalItems = config.mediaList.size)

            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(errorMessage = t.message)
                _effects.emit(PlayerEffect.ShowErrorToast(t.message ?: "Erro desconhecido"))
            }
        }
    }

    /**
     * Estratégia PreloadManager — usada quando mediaList.size > 1.
     * Registra todos os itens no manager, aciona o pré-carregamento e reproduz o primeiro.
     */
    @UnstableApi
    private fun loadWithPreload(config: PlayerConfig) {
        _player.stop()
        _player.clearMediaItems()
        preloadManager.reset()
        currentPlayingIndex = 0

        config.mediaList.forEachIndexed { index, item ->
            preloadManager.add(item.toMediaItem(), index)
        }
        preloadManager.invalidate()
        playItemAt(0)
    }

    /**
     * Estratégia direta — usada quando mediaList.size == 1.
     * Carrega o MediaSource diretamente no ExoPlayer sem overhead do PreloadManager.
     */
    @UnstableApi
    private fun loadDirect(config: PlayerConfig) {
        _player.stop()
        _player.clearMediaItems()
        preloadManager.reset() // limpa estado anterior se havia uma playlist
        currentPlayingIndex = 0

        config.mediaList.forEach { item ->
            val source = MediaSourceBuilder.build(app, item, config.cacheConfig)
            _player.addMediaSource(source)
        }
        _player.playWhenReady = true
        _player.prepare()

        _uiState.value = _uiState.value.copy(
            currentIndex = 0,
            currentPositionMs = 0L,
            durationMs = 0L
        )
    }

    /**
     * Reproduz o item no índice indicado via PreloadManager.
     * Tenta obter o MediaSource pré-carregado; usa fallback direto se ainda não estiver pronto.
     */
    @UnstableApi
    private fun playItemAt(index: Int) {
        if (index < 0 || index >= loadedMediaConfigs.size) return

        currentPlayingIndex = index
        val config = loadedMediaConfigs[index]
        val mediaItem = config.toMediaItem()

        val preloadedSource = preloadManager.getMediaSource(mediaItem)

        if (preloadedSource != null) {
            _player.setMediaSource(preloadedSource)
        } else {
            // Fallback: pré-carregamento ainda não concluído, carrega diretamente
            val directSource = MediaSourceBuilder.build(
                app,
                config,
                lastConfig?.cacheConfig ?: CacheConfig()
            )
            _player.setMediaSource(directSource)
        }

        _player.playWhenReady = true
        _player.prepare()

        // Reprioriza os vizinhos para pré-carregamento
        preloadManager.setCurrentPlayingIndex(index)
        preloadManager.invalidate()

        _uiState.value = _uiState.value.copy(
            currentIndex = index,
            currentPositionMs = 0L,
            durationMs = 0L
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            _player.removeListener(playerListener)
            preloadManager.release() // liberar PreloadManager ANTES do player
            _player.release()
        } catch (_: Exception) {
        }
    }
}
