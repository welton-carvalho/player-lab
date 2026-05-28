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
    val bufferConfig: BufferConfig = BufferConfig(),
    // Força playlist interna do ExoPlayer (seekTo) mesmo com múltiplos itens,
    // ignorando o DefaultPreloadManager. Ideal para Reels: troca de item quase instantânea.
    val forcePlaylistMode: Boolean = false
)
