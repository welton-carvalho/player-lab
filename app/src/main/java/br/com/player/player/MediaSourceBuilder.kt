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

    @OptIn(UnstableApi::class)
    fun build(
        context: Context,
        config: MediaItemConfig,
        cacheConfig: CacheConfig = CacheConfig()
    ): MediaSource {
        val cacheFactory: CacheDataSource.Factory =
            CacheManager.getCacheDataSourceFactory(context, cacheConfig)

        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheFactory)
            .createMediaSource(config.toMediaItem())
    }
}

/**
 * Extension to convert MediaItemConfig to a Media3 MediaItem,
 * preserving format MIME type and optional clip duration.
 */
@UnstableApi
fun MediaItemConfig.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setUri(url)
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
