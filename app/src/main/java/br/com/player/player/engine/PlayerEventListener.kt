package br.com.player.player.engine

/**
 * Contrato de eventos do player — sem dependências do Android Framework ou Media3,
 * permitindo implementações fake em testes JVM puros.
 */
interface PlayerEventListener {
    fun onIsPlayingChanged(isPlaying: Boolean) = Unit
    fun onPlaybackStateChanged(isBuffering: Boolean, isEnded: Boolean) = Unit
    fun onPositionChanged(positionMs: Long, durationMs: Long) = Unit
    fun onMediaItemIndexChanged(index: Int) = Unit
    fun onPreloadCompleted(index: Int) = Unit
    /** Renderizador desenhou o 1º frame do item atual — sinal para esmaecer o poster. */
    fun onFirstFrameRendered() = Unit
}
