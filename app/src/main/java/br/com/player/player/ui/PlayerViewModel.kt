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
    val totalItems: Int = 0,
    val isPrefetchEnabled: Boolean = false,
    val preloadedIndices: Set<Int> = emptySet()
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

    /**
     * Índice do item cujo 1º frame já foi renderizado pelo player. Composables de poster
     * observam isto para esmaecer no momento exato em que o pixel real aparece — sem
     * isso, há flash preto entre o fim do buffer e o início da pintura. `null` enquanto
     * nenhum frame foi desenhado no item atual (estado entre `onMediaItemTransition` e
     * o próximo `onRenderedFirstFrame`).
     */
    private val _firstFrameRenderedIndex = MutableStateFlow<Int?>(null)
    val firstFrameRenderedIndex: StateFlow<Int?> = _firstFrameRenderedIndex.asStateFlow()

    private var currentPlayingIndex: Int = 0
    private var loadedMediaConfigs: List<MediaItemConfig> = emptyList()
    private var lastConfig: PlayerConfig? = null
    private var prefetchEnabled: Boolean = false

    /**
     * Posição (ms) salva por índice — alimenta a retomada estilo Instagram: ao voltar a um
     * item já reproduzido na sessão, continua de onde parou. Em memória apenas; process
     * death reseta (por design — combinado com o usuário).
     */
    private val savedPositions = mutableMapOf<Int, Long>()

    init {
        engine.setEventListener(object : PlayerEventListener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(isBuffering: Boolean, isEnded: Boolean) {
                _uiState.value = _uiState.value.copy(isBuffering = isBuffering)
                if (isEnded) {
                    // Vídeo chegou ao fim → próxima visita deve reiniciar do 0,
                    // não voltar para o último ms tocado (que seria ~duração).
                    savedPositions.remove(currentPlayingIndex)
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
                // Reset do flag *antes* de propagar o novo índice: o poster da página
                // que está entrando volta opaco imediatamente, cobrindo qualquer pixel
                // remanescente do item anterior, até `onFirstFrameRendered` reativar.
                _firstFrameRenderedIndex.value = null
                if (!prefetchEnabled) {
                    currentPlayingIndex = index
                    _uiState.value = _uiState.value.copy(currentIndex = index)
                }
            }

            override fun onPreloadCompleted(index: Int) {
                _uiState.value = _uiState.value.copy(
                    preloadedIndices = _uiState.value.preloadedIndices + index
                )
            }

            override fun onFirstFrameRendered() {
                // Sem parâmetro de índice na API do Media3 — lemos o índice corrente
                // do engine. O flush de `seekToItem` garante que o frame renderizado
                // pertence ao item atual.
                _firstFrameRenderedIndex.value = engine.currentMediaItemIndex
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
                    if (intent.index != currentPlayingIndex) {
                        captureCurrentPosition()
                        playItemAt(intent.index)
                    }
                } else {
                    if (intent.index != currentPlayingIndex) captureCurrentPosition()
                    // Em forcePlaylistMode (Reels), retoma da posição salva do destino.
                    engine.seekToItem(intent.index, savedPositions[intent.index] ?: 0L)
                }
            }
            PlayerIntent.NextItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex < loadedMediaConfigs.size - 1) {
                        captureCurrentPosition()
                        playItemAt(currentPlayingIndex + 1)
                    }
                } else {
                    engine.seekToNext()
                }
            }
            PlayerIntent.PreviousItem -> {
                if (prefetchEnabled) {
                    if (currentPlayingIndex > 0) {
                        captureCurrentPosition()
                        playItemAt(currentPlayingIndex - 1)
                    }
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
                prefetchEnabled = config.mediaList.size > 1 && !config.forcePlaylistMode
                loadedMediaConfigs = config.mediaList
                _uiState.value = _uiState.value.copy(
                    errorMessage = null,
                    isPrefetchEnabled = prefetchEnabled,
                    preloadedIndices = emptySet()
                )

                // Nova carga zera estado da sessão anterior — posições salvas só fazem
                // sentido dentro da mesma playlist; trocar de lista invalida tudo.
                savedPositions.clear()
                _firstFrameRenderedIndex.value = null

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
        val startPositionMs = savedPositions[index] ?: 0L
        engine.playPreloadedItemAt(
            index = index,
            config = config,
            cacheConfig = lastConfig?.cacheConfig ?: CacheConfig(),
            startPositionMs = startPositionMs
        )
        engine.setCurrentPreloadIndex(index)
        engine.invalidatePreload()
        _firstFrameRenderedIndex.value = null
        _uiState.value = _uiState.value.copy(
            currentIndex = index,
            currentPositionMs = startPositionMs,
            durationMs = 0L
        )
    }

    /**
     * Captura a posição atual antes de trocar de item — com guarda para não sobrescrever
     * uma posição real com `0L` durante recargas em que `currentPosition`/`duration` ainda
     * não foram populados. Chamada apenas quando há troca real de índice.
     */
    private fun captureCurrentPosition() {
        val pos = engine.currentPosition
        val dur = engine.duration
        if (pos > 0L && dur > 0L) {
            savedPositions[currentPlayingIndex] = pos
        }
    }

    /** Exposto como `internal` para permitir limpeza controlada em testes. */
    internal fun closeForTest() = onCleared()

    override fun onCleared() {
        super.onCleared()
        engine.clearEventListener()
        engine.release()
    }
}
