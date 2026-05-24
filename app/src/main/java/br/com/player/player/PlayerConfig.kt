package br.com.player.player


/**
 * Configuration data models for the player module.
 */
data class MediaItemConfig(
    val url: String,
    val format: MediaFormat = MediaFormat.HLS,
    val clipDurationMs: Long? = null
)

enum class MediaFormat { HLS, DASH }

data class CacheConfig(
    val maxBytes: Long = 200L * 1024L * 1024L // 200 MB default
)

data class BufferConfig(
    val minBufferMs: Int = 5000,
    val maxBufferMs: Int = 50000,
    val bufferForPlaybackMs: Int = 2500,
    val bufferForPlaybackAfterRebufferMs: Int = 5000
)

data class PlayerConfig(
    val mediaList: List<MediaItemConfig>,
    val cacheConfig: CacheConfig = CacheConfig(),
    val bufferConfig: BufferConfig = BufferConfig()
)

/**
 * Controla como o vídeo é dimensionado dentro do container do player.
 *
 * Modos de escala (container = fillMaxSize, sem barras externas):
 * - [FillBounds] — estica para preencher; pode distorcer se proporção diferir.
 * - [Crop]       — zoom para preencher sem distorção; corta as bordas.
 * - [Inside]     — como Fit, mas nunca amplia além do tamanho original.
 *
 * Modo de proporção fixa (container forçado + ContentScale.Fit):
 * - [Fixed]      — letterbox/pillarbox automático com barras pretas.
 *
 * Use os atalhos do companion para as proporções mais comuns:
 * [RATIO_16_9], [RATIO_4_3], [RATIO_1_1], [RATIO_9_16].
 */
sealed class AspectRatioMode {

    // ── Modos de escala (container fullscreen) ──────────────────────────────

    /**
     * Estica o vídeo para preencher todo o espaço.
     * Pode distorcer se a proporção do vídeo for diferente da tela.
     * → ContentScale.FillBounds
     */
    data object FillBounds : AspectRatioMode()

    /**
     * Amplia/recorta o vídeo para preencher sem distorção.
     * As bordas podem ser cortadas se as proporções diferirem.
     * → ContentScale.Crop
     */
    data object Crop : AspectRatioMode()

    /**
     * Encaixa o vídeo preservando a proporção; nunca amplia além do original.
     * → ContentScale.Inside
     */
    data object Inside : AspectRatioMode()

    // ── Modo de proporção fixa ───────────────────────────────────────────────

    /**
     * Força o container a uma proporção específica (ex: 16×9).
     * O vídeo é encaixado com [ContentScale.Fit]; barras pretas cobrem as sobras.
     */
    data class Fixed(val widthRatio: Int, val heightRatio: Int) : AspectRatioMode() {
        val ratio: Float get() = widthRatio.toFloat() / heightRatio.toFloat()
        override fun toString() = "$widthRatio:$heightRatio"
    }

    companion object {
        val RATIO_16_9 = Fixed(16, 9)
        val RATIO_4_3  = Fixed(4, 3)
        val RATIO_1_1  = Fixed(1, 1)
        val RATIO_9_16 = Fixed(9, 16)
    }
}

