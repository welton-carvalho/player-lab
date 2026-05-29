@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package br.com.player.reels

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.compose.ContentFrame
import br.com.player.feed.VideoFeedMock
import br.com.player.player.BufferConfig
import br.com.player.player.PlayerConfig
import br.com.player.player.ui.PlayerIntent
import br.com.player.player.ui.PlayerViewModel
import br.com.player.thumbnail.VideoPoster
import br.com.player.util.playerViewModel
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Feed de Reels: páginas full-screen com swipe vertical e snap (uma por vez).
 *
 * Reaproveita integralmente o módulo de player: o [PlayerViewModel] (com `DefaultPreloadManager`,
 * cache e regras de playback) atua como lib. Os 30 itens (mesmo vídeo) são carregados como
 * playlist (`LoadMediaList`), ligando o **preload** dos vizinhos; a página assentada dispara
 * `PlayItemAt`, trocando para a fonte já pré-carregada.
 *
 * Diferenças em relação ao feed de cards:
 *  - [VerticalPager] com snap (em vez de LazyColumn de rolagem livre);
 *  - tela imersiva, sem TopAppBar, vídeo encaixado com `ContentScale.Fit`;
 *  - **trava a orientação em portrait** apenas enquanto esta tela estiver visível;
 *  - toque na tela pausa/retoma (sem controles de chrome).
 */
// Buffer otimizado para Reels (estilo TikTok):
// - bufferForPlaybackMs baixo → começa a tocar mal chegam 800ms de dados
// - minBufferMs menor → libera banda para o ExoPlayer avançar para o próximo item
// - maxBufferMs reduzido → não acumula 50s no item atual; avança playlist mais cedo
// - back-buffer de 10s (configurado no LoadControl do ExoPlayerEngine) → swipe-back imediato
private val ReelsBufferConfig = BufferConfig(
    minBufferMs = 3_000,
    maxBufferMs = 20_000,
    bufferForPlaybackMs = 800,
    bufferForPlaybackAfterRebufferMs = 2_000
)

@Composable
fun ReelsScreen(
    viewModel: PlayerViewModel = playerViewModel(bufferConfig = ReelsBufferConfig)
) {
    val uiState by viewModel.uiState.collectAsState()
    val firstFrameRenderedIndex by viewModel.firstFrameRenderedIndex.collectAsState()
    val items = VideoFeedMock.items
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Carrega os 30 itens como playlist interna do ExoPlayer.
    // `forcePlaylistMode = true` evita o caminho DefaultPreloadManager (que faz
    // setMediaSource + prepare a cada swipe — sempre dispara STATE_BUFFERING) e
    // roteia trocas via `seekToItem(index, 0L)`, sem tear-down do pipeline.
    LaunchedEffect(Unit) {
        viewModel.handleIntent(
            PlayerIntent.LoadMediaList(
                PlayerConfig(
                    mediaList = items.map { it.config },
                    forcePlaylistMode = true
                )
            )
        )
    }

    // Trava portrait + imersivo, escopado a esta tela. Revertido ao sair (onDispose).
    val activity = LocalContext.current as Activity
    DisposableEffect(Unit) {
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity.requestedOrientation = previousOrientation // volta a liberar rotação
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Autoplay: dispara o seek assim que o pager determina o destino (durante a animação),
    // antes de assentar — dá ao ExoPlayer ~300-500ms extras de buffer antes do vídeo aparecer.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { page -> viewModel.handleIntent(PlayerIntent.PlayItemAt(page)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        ContentFrame(
            player = viewModel.getPlayer(),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Graça de 300 ms antes de exibir o spinner: microblips de STATE_BUFFERING
        // disparados por `seekToItem` (troca entre itens preloadados) se cancelam
        // antes do delay e ficam invisíveis. Buffering real (rede lenta) ainda
        // acende o indicador. LaunchedEffect cancela o delay na transição,
        // garantindo que showSpinner só vire true se isBuffering persistir.
        val isBuffering = uiState.isBuffering
        var showSpinner by remember { mutableStateOf(false) }
        LaunchedEffect(isBuffering) {
            if (!isBuffering) {
                showSpinner = false
            } else {
                delay(300)
                showSpinner = true
            }
        }
        if (showSpinner) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        // Pager transparente por cima: captura apenas o swipe vertical (troca de vídeo)
        // e o toque (pausa/retoma). Não hospeda vídeo, então nada se move ao deslizar.
        // Cada página renderiza um VideoPoster (1º frame do item) que cobre o ContentFrame
        // até o player desenhar o frame real do item — esmaece via fade quando a tupla
        // (página atual && 1º frame renderizado) bate. Durante o swipe, nenhuma página
        // satisfaz a condição → ambos posters ficam opacos, cobrindo o pixel do vídeo
        // anterior. Estilo Instagram.
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            // `derivedStateOf` evita recompor o Box desta página a cada mudança de
            // `pagerState.currentPage` (que dispara durante todo o swipe). A página só
            // recompõe quando o *resultado booleano* muda — para 29 das 30 páginas, isso
            // nunca acontece em um swipe individual.
            val posterVisible by remember(page) {
                derivedStateOf {
                    !(page == pagerState.currentPage && page == firstFrameRenderedIndex)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.handleIntent(PlayerIntent.TogglePlayPause) }
            ) {
                VideoPoster(
                    mediaUrl = items[page].config.url,
                    modifier = Modifier.fillMaxSize(),
                    visible = posterVisible,
                    contentScale = ContentScale.Fit
                )

                // Shown only when prefetch is active and the next page hasn't buffered yet.
                // Visible only during a partial swipe — mirrors Instagram's thin bottom bar.
                val isNextPage by remember(page) {
                    derivedStateOf { page == pagerState.currentPage + 1 }
                }
                if (uiState.isPrefetchEnabled && isNextPage && page !in uiState.preloadedIndices) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        color = Color.White.copy(alpha = 0.5f),
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }
}
