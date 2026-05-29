@file:OptIn(UnstableApi::class)

package br.com.player.thumbnail

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.frame.FrameExtractor
import br.com.player.player.CacheConfig
import br.com.player.player.MediaSourceBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache em memória de "primeiros frames" extraídos dos vídeos para servir como poster
 * antes/durante o buffer — estilo Instagram.
 *
 * **Por que FrameExtractor:** roda o pipeline do próprio ExoPlayer (HLS,
 * `OkHttpDataSource`, cache de 200 MB do projeto), enquanto `MediaMetadataRetriever`
 * usa o Stagefright e quebra com HLS em vários OEMs (não respeita o `CacheDataSource`).
 *
 * **Chave do cache = URL** (não índice) — colapsa as 30 entradas idênticas do mock numa
 * única extração. Os 30 itens da playlist têm a mesma URL → 1 frame extraído atende todos.
 *
 * Threading: o [FrameExtractor] na 1.10+ é construído **por MediaItem** (não reutilizável
 * — cada `build()` sobe um renderer pipeline novo). Como `extract` é chamada por URL
 * única (dedup via [inFlight] + LRU permanente até evicção), o custo é amortizado: para
 * 30 itens com a mesma URL, há **1** extração no ciclo de vida do app.
 *
 * Memória: bitmaps são reescalados para [TARGET_WIDTH_PX] (~360 px → ~640 KB ARGB_8888 a
 * 16:9). LRU de [MAX_ENTRIES] entradas → teto de ~15 MB. Sem `Bitmap.recycle()` porque
 * Compose pode estar exibindo a bitmap evict; o GC libera quando ninguém mais referencia.
 *
 * Falhas (timeout, exceção, codec não suportado): logam e deixam o StateFlow em `null`.
 * Sem fallback — o `VideoPoster` mostra `Box` preto, idêntico ao comportamento anterior.
 */
@UnstableApi
class ThumbnailCache private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()

    @GuardedBy("lock")
    private val lru: LinkedHashMap<String, Bitmap> =
        object : LinkedHashMap<String, Bitmap>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>): Boolean {
                val evict = size > MAX_ENTRIES
                if (evict) {
                    // Solta a referência forte; collectors do StateFlow seguram a bitmap
                    // enquanto a tela usa, depois GC libera. Não chamar `recycle()` por isso.
                    flows[eldest.key]?.value = null
                }
                return evict
            }
        }

    @GuardedBy("lock")
    private val flows: MutableMap<String, MutableStateFlow<Bitmap?>> = mutableMapOf()

    @GuardedBy("lock")
    private val inFlight: MutableMap<String, Job> = mutableMapOf()

    /**
     * Devolve um [StateFlow] que emite a bitmap do 1º frame da [url] quando pronta. Dispara
     * extração assíncrona na primeira observação por URL; chamadas subsequentes reusam o
     * mesmo flow. Composables devem coletar via `produceState` ou `collectAsState`.
     */
    fun observe(url: String): StateFlow<Bitmap?> {
        synchronized(lock) {
            val existing = flows[url]
            if (existing != null) return existing.asStateFlow()
            val newFlow = MutableStateFlow(lru[url])
            flows[url] = newFlow
            if (newFlow.value == null) startExtraction(url)
            return newFlow.asStateFlow()
        }
    }

    @GuardedBy("lock")
    private fun startExtraction(url: String) {
        if (inFlight[url] != null) return // dedup
        val job = scope.launch {
            val bitmap = runCatching { extract(url) }
                .onFailure { Log.w(TAG, "Falha ao extrair frame de $url", it) }
                .getOrNull()
            synchronized(lock) {
                inFlight.remove(url)
                if (bitmap != null) {
                    lru[url] = bitmap // pode disparar evicção do menos usado
                    flows[url]?.value = bitmap
                }
            }
        }
        inFlight[url] = job
    }

    private suspend fun extract(url: String): Bitmap? = withContext(Dispatchers.IO) {
        // MIME type explícito — sem isso, em alguns casos o FrameExtractor não infere HLS
        // mesmo com `.m3u8` na URL, e a extração falha silenciosamente com a bitmap nula.
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(detectMimeType(url))
            .build()
        val extractor = FrameExtractor.Builder(app, mediaItem)
            // Reusa o mesmo factory de mídia (HLS + cache de 200 MB) do pipeline de
            // playback, garantindo que bytes baixados aqui ajudam (não competem com) o
            // cache do player.
            .setMediaSourceFactory(MediaSourceBuilder.createFactory(app, CacheConfig()))
            .build()
        try {
            Log.d(TAG, "Extraindo frame em ${POSTER_POSITION_MS}ms de $url")
            // Extração com offset explícito (não `getFrame(0L)` nem `getThumbnail()`):
            // muitas streams começam com slate preto / fade-in (inclusive a de teste da
            // Mux usada no mock) e `getThumbnail()` faz fallback pra posição 0 quando a
            // heurística interna não acha nada melhor. Pegar em 2s garante conteúdo real.
            // Para vídeos menores que isso, o seek é clampado pro fim — não falha.
            val deferred = CompletableDeferred<Bitmap?>()
            val future = extractor.getFrame(POSTER_POSITION_MS)
            future.addListener(
                {
                    runCatching { future.get().bitmap }
                        .onSuccess { deferred.complete(it) }
                        .onFailure { deferred.completeExceptionally(it) }
                },
                { r -> r.run() }
            )
            val raw = deferred.await() ?: return@withContext null
            Log.d(TAG, "Frame extraído: ${raw.width}x${raw.height} config=${raw.config}")
            val scaled = downscale(raw)
            Log.d(TAG, "Frame downscaled: ${scaled.width}x${scaled.height} config=${scaled.config}")
            scaled
        } finally {
            // Libera o renderer pipeline imediatamente — extrações futuras criam outro
            // (na 1.10 cada FrameExtractor é vinculado a um MediaItem).
            extractor.close()
        }
    }

    /** Heurística simples de MIME type baseada na extensão; HLS/DASH são o que o app usa. */
    private fun detectMimeType(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        url.contains(".mpd", ignoreCase = true)  -> MimeTypes.APPLICATION_MPD
        else                                     -> MimeTypes.VIDEO_MP4
    }

    /**
     * Reescala para [TARGET_WIDTH_PX] mantendo aspect e garante config software ARGB_8888.
     *
     * **Por que ARGB_8888 software:** o `FrameExtractor` no Android 8+ devolve bitmaps com
     * `Bitmap.Config.HARDWARE`, que **não podem** ser passadas para `createScaledBitmap`
     * (lança `IllegalArgumentException`) e podem dar problema em `asImageBitmap()` no
     * Compose dependendo do contexto. Copiamos para software ARGB_8888 antes de escalar.
     */
    private fun downscale(src: Bitmap): Bitmap {
        val software = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
                ?: return src // fallback teórico — copy() raramente retorna null
        } else {
            src
        }
        if (software.width <= TARGET_WIDTH_PX) return software
        val targetW = TARGET_WIDTH_PX
        val targetH = (software.height.toLong() * targetW / software.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(software, targetW, targetH, /* filter = */ true)
    }

    companion object {
        private const val TAG = "ThumbnailCache"
        private const val MAX_ENTRIES = 24
        private const val TARGET_WIDTH_PX = 360

        /** Offset (ms) onde o frame do poster é extraído — evita slates pretos iniciais. */
        private const val POSTER_POSITION_MS = 2_000L

        @Volatile
        private var INSTANCE: ThumbnailCache? = null

        fun get(app: Application): ThumbnailCache =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThumbnailCache(app).also { INSTANCE = it }
            }
    }
}
