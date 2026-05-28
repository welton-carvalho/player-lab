package br.com.player.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * Factory to build MediaSource from our MediaItemConfig.
 */
object MediaSourceBuilder {

    /**
     * Cria um [DefaultMediaSourceFactory] configurado com o cache de disco.
     * Exposto para que [ExoPlayerEngine] possa injetá-lo no [DefaultPreloadManager],
     * garantindo que bytes preloadados sejam gravados no mesmo cache de 200 MB.
     */
    @OptIn(UnstableApi::class)
    fun createFactory(
        context: Context,
        cacheConfig: CacheConfig = CacheConfig()
    ): DefaultMediaSourceFactory {
        val cacheDataSourceFactory = CacheManager.getCacheDataSourceFactory(context, cacheConfig)
        return DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory)
    }

    @OptIn(UnstableApi::class)
    fun build(
        context: Context,
        config: MediaItemConfig,
        cacheConfig: CacheConfig = CacheConfig()
    ): MediaSource {
        return createFactory(context, cacheConfig).createMediaSource(config.toMediaItem())
    }
}

/**
 * Extension to convert MediaItemConfig to a Media3 MediaItem,
 * preserving format MIME type and optional clip duration.
 *
 * [mediaId] torna o MediaItem único mesmo quando a URL se repete. Necessário para o
 * [DefaultPreloadManager], que indexa por `MediaItem`: sem um id distinto, itens com a
 * mesma URL são considerados iguais e colapsam em uma única entrada de preload.
 */
@UnstableApi
fun MediaItemConfig.toMediaItem(mediaId: String? = null): MediaItem {
    return MediaItem.Builder()
        .setUri(url)
        .apply { if (mediaId != null) setMediaId(mediaId) }
        .setMimeType(
            when (format) {
                MediaFormat.HLS -> MimeTypes.APPLICATION_M3U8
                MediaFormat.DASH -> MimeTypes.APPLICATION_MPD
            }
        )
        .apply {
            if (clipDurationMs != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(clipDurationMs)
                        .build()
                )
            }
        }
        .build()
}
