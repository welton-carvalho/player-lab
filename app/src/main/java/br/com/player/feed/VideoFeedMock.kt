package br.com.player.feed

import br.com.player.player.MediaFormat
import br.com.player.player.MediaItemConfig

/**
 * Um item do feed: a posição (1-based) exibida no card + a configuração de mídia
 * usada para tocar/pré-carregar o vídeo daquela posição.
 */
data class FeedItem(
    val position: Int,
    val config: MediaItemConfig
)

/**
 * Mock estático do feed.
 *
 * Gera [COUNT] itens usando a **mesma** URL HLS de teste — suficiente para validar
 * o autoplay do card mais visível e o prefetch dos vizinhos via DefaultPreloadManager.
 */
object VideoFeedMock {

    const val COUNT = 30

    /** Mesma stream de teste já usada na MainScreen. */
    private const val SAMPLE_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    val items: List<FeedItem> = List(COUNT) { index ->
        FeedItem(
            position = index + 1,
            config = MediaItemConfig(url = SAMPLE_URL, format = MediaFormat.HLS)
        )
    }
}
