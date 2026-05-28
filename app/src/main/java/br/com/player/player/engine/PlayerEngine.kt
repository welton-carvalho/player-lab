package br.com.player.player.engine

import androidx.media3.common.Player
import br.com.player.player.CacheConfig
import br.com.player.player.MediaItemConfig

/**
 * Abstrai toda interação com ExoPlayer + PreloadManager.
 *
 * [player] é a única propriedade que referencia um tipo Media3 e destina-se exclusivamente
 * ao ContentFrame da UI. Testes de ViewModel não devem chamá-la.
 */
interface PlayerEngine {

    /** Instância Media3 para o ContentFrame. Não usar em testes unitários do ViewModel. */
    val player: Player

    fun setEventListener(listener: PlayerEventListener)
    fun clearEventListener()

    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val currentMediaItemIndex: Int

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekToItem(index: Int, positionMs: Long = 0L)
    fun seekToNext()
    fun seekToPrevious()

    /** Carrega diretamente no ExoPlayer (1 item ou playlist sem preload). */
    fun loadDirect(items: List<MediaItemConfig>, cacheConfig: CacheConfig)

    /** Registra todos os itens no PreloadManager e dispara o preload inicial. */
    fun registerForPreload(items: List<MediaItemConfig>)

    /**
     * Reproduz o item no [index] usando a fonte pré-carregada se disponível;
     * usa [config] como fallback direto caso o preload ainda não tenha terminado.
     */
    fun playPreloadedItemAt(index: Int, config: MediaItemConfig, cacheConfig: CacheConfig)

    fun setCurrentPreloadIndex(index: Int)
    fun invalidatePreload()
    fun resetPreload()

    /** Forces immediate preload of [index] regardless of its distance from the current item. */
    fun requestPreloadAt(index: Int) = Unit

    fun release()
}
