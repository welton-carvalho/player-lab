@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package br.com.player.thumbnail

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * Poster com o 1º frame do vídeo apontado por [mediaUrl]. Coberto pela camada do
 * player; esmaece (alpha fade) quando [visible] vira `false`, ou seja, quando o
 * `ContentFrame` desenhou o frame real do mesmo item.
 *
 * Enquanto a bitmap ainda não foi extraída, mostra `Box` preto — mantém o mesmo
 * visual que existia antes do feature (sem flash de cor).
 *
 * - `asImageBitmap()` é zero-copy (envelopa a `Bitmap`), então recompor o Image
 *   não realoca.
 * - `Modifier.graphicsLayer { alpha = ... }` é GPU op, não dispara recomposição.
 */
@Composable
fun VideoPoster(
    mediaUrl: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop
) {
    val app = LocalContext.current.applicationContext as Application
    val cache = remember(app) { ThumbnailCache.get(app) }
    // collectAsState direto sobre o StateFlow do cache pega o valor atual sincronamente —
    // evita o flicker de 1 frame que `produceState(initialValue = null)` introduzia ao
    // recompor um card já visto.
    val bitmap by remember(mediaUrl, cache) { cache.observe(mediaUrl) }.collectAsState()
    // Diagnóstico temporário — remover quando o feature estabilizar.
    Log.d("VideoPoster", "recompose url=$mediaUrl bitmap=${bitmap?.let { "${it.width}x${it.height}" } ?: "null"} visible=$visible")
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "posterFade"
    )

    // Alpha aplicado de duas formas distintas (não via `graphicsLayer` no Box pai):
    //  - no `Image` via o parâmetro nativo `alpha` — só multiplica no draw, sem criar
    //    render layer, sem cache de GPU para invalidar.
    //  - no placeholder preto via `Modifier.alpha`.
    //
    // Tentativa anterior com `graphicsLayer { alpha }` no Box pai funcionava mas tinha
    // um bug: quando a bitmap chegava por recomposição, o conteúdo do layer cacheado não
    // era invalidado e o `Image` só aparecia após uma re-layout do LazyColumn (scroll).
    Box(modifier = modifier) {
        val current = bitmap
        if (current == null) {
            // Placeholder enquanto a extração não terminou — mesmo "preto" do design
            // anterior, mas escopo limitado ao período de loading.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha)
                    .background(Color.Black)
            )
        } else {
            // `asImageBitmap()` aloca um wrapper; memoizar enquanto a Bitmap subjacente
            // for a mesma evita realocação a cada recomposição (fade do `visible`).
            val imageBitmap = remember(current) { current.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = contentScale,
                alpha = alpha,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
