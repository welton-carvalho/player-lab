package br.com.player.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File

/**
 * Singleton responsible for providing a Cache-backed DataSource.Factory.
 * Thread-safe lazy initialization of SimpleCache.
 */
@UnstableApi
object CacheManager {

    @Volatile
    private var simpleCache: SimpleCache? = null
    
    @Volatile
    private var okHttpClient: OkHttpClient? = null

    private val lock = Any()

    private fun getOkHttpClient(): OkHttpClient {
        okHttpClient?.let { return it }
        synchronized(lock) {
            okHttpClient?.let { return it }
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                // Fallback to HTTP/1.1 can solve some StreamReset issues in emulators
                .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        .build()
                    chain.proceed(request)
                }
                .build()
            okHttpClient = client
            return client
        }
    }

    fun getCache(context: Context, cacheConfig: CacheConfig, externalClient: OkHttpClient? = null): SimpleCache {
        simpleCache?.let { return it }
        synchronized(lock) {
            simpleCache?.let { return it }
            val cacheDir = File(context.cacheDir, "mediacache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val evictor = LeastRecentlyUsedCacheEvictor(cacheConfig.maxBytes)
            // SimpleCache(cacheDir, evictor) - in newer Media3, constructor signature may vary
            val cache = SimpleCache(cacheDir, evictor)
            simpleCache = cache
            return cache
        }
    }

    fun getCacheDataSourceFactory(context: Context, cacheConfig: CacheConfig, externalClient: OkHttpClient? = null): CacheDataSource.Factory {
        val cache = getCache(context, cacheConfig, externalClient)
        val client = externalClient ?: getOkHttpClient()
        val upstreamFactory = OkHttpDataSource.Factory(client)

        val cacheDataSinkFactory = CacheDataSink.Factory().setCache(cache)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun release() {
        synchronized(lock) {
            try {
                simpleCache?.release()
            } catch (_: Exception) {
            }
            simpleCache = null
        }
    }
}

