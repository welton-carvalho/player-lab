package br.com.player.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import br.com.player.player.CacheConfig
import br.com.player.player.MediaItemConfig
import br.com.player.player.PlayerConfig
import br.com.player.player.engine.PlayerEngine
import br.com.player.player.engine.PlayerEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── MVI contract ─────────────────────────────────────────────────────────────

sealed class PlayerIntent {
    object TogglePlayPause : PlayerIntent()
    data class SeekTo(val positionMs: Long) : PlayerIntent()
    data class LoadMediaList(val config: PlayerConfig) : PlayerIntent()
    data class PlayItemAt(val index: Int) : PlayerIntent()
    object NextItem : PlayerIntent()
    object PreviousItem : PlayerIntent()
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
 * ViewModel MVI que gerencia a lógica de playback delegando operações de mídia
 * ao [PlayerEngine].
 *
 * Não depende de nenhum tipo Android ou Media3 diretamente — apenas de
 * [PlayerEngine] e [PlayerEventListener] — o que permite testes JVM puros.
 */
class PlayerViewModel(
    private val engine: PlayerEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PlayerEffect>()
    val effects = _effects.asSharedFlow()

    private var currentPlayingIndex: Int = 0
    private var loadedMediaConfigs: List<MediaItemConfig> = emptyList()
    private var lastConfig: PlayerConfig? = null
    private var prefetchEnabled: Boolean = false

    init {
        engine.setEventListener(object : PlayerEventListener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(isBuffering: Boolean, isEnded: Boolean) {
                _uiState.value = _uiState.value.copy(isBuffering = isBuffering)
                if (isEnded) {
                    viewModelScope.launch { _effects.emit(PlayerEffect.OnPlaylistEnded) }
                }
            }

            override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = positionMs,
                    durationMs = durationMs
                )
            }

            override fun onMediaItemIndexChanged(index: Int) {
                if (!prefetchEnabled) {
                    currentPlayingIndex = index
                    _uiState.value = _uiState.value.copy(currentIndex = index)
                }
            }
        })
        startPositionUpdates()
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                if (engine.isPlaying) {
                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = engine.currentPosition,
                        durationMs = engine.duration.coerceAtLeast(0L)
                    )
                }
                delay(500L)
            }
        }
    }

    /** Expõe o player Media3 para uso exclusivo pelo ContentFrame da UI. */
    fun getPlayer(): Player = engine.player

    fun handleIntent(intent: PlayerIntent) {
        when (intent) {
            is PlayerIntent.TogglePlayPause -> {
                if (engine.isPlaying) engine.pause() else engine.play()
            }
            is PlayerIntent.SeekTo -> engine.seekTo(intent.positionMs)
            is PlayerIntent.LoadMediaList -> {
                lastConfig = intent.config
                loadMediaList(intent.config)
            }
            is PlayerIntent.PlayItemAt -> {
                if (prefetchEnabled) {
                    if (intent.index != currentPlayingIndex) playItemAt(intent.index)
                } else {
                    engine.seekToItem(intent.index, 0L)
                }
            }
            PlayerIntent.NextItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex < loadedMediaConfigs.size - 1)
                        playItemAt(currentPlayingIndex + 1)
                } else {
                    engine.seekToNext()
                }
            }
            PlayerIntent.PreviousItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex > 0)
                        playItemAt(currentPlayingIndex - 1)
                } else {
                    engine.seekToPrevious()
                }
            }
            PlayerIntent.RetryLast -> lastConfig?.let { loadMediaList(it) }
        }
    }

    private fun loadMediaList(config: PlayerConfig) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(errorMessage = null)
                prefetchEnabled = config.mediaList.size > 1
                loadedMediaConfigs = config.mediaList

                if (prefetchEnabled) {
                    engine.registerForPreload(config.mediaList)
                    playItemAt(0)
                } else {
                    engine.loadDirect(config.mediaList, config.cacheConfig)
                    _uiState.value = _uiState.value.copy(
                        currentIndex = 0,
                        currentPositionMs = 0L,
                        durationMs = 0L
                    )
                }

                _uiState.value = _uiState.value.copy(totalItems = config.mediaList.size)
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(errorMessage = t.message)
                _effects.emit(PlayerEffect.ShowErrorToast(t.message ?: "Erro desconhecido"))
            }
        }
    }

    private fun playItemAt(index: Int) {
        if (index < 0 || index >= loadedMediaConfigs.size) return
        currentPlayingIndex = index
        val config = loadedMediaConfigs[index]
        engine.playPreloadedItemAt(index, config, lastConfig?.cacheConfig ?: CacheConfig())
        engine.setCurrentPreloadIndex(index)
        engine.invalidatePreload()
        _uiState.value = _uiState.value.copy(
            currentIndex = index,
            currentPositionMs = 0L,
            durationMs = 0L
        )
    }

    /** Exposto como `internal` para permitir limpeza controlada em testes. */
    internal fun closeForTest() = onCleared()

    override fun onCleared() {
        super.onCleared()
        engine.clearEventListener()
        engine.release()
    }
}
