@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.player.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.player.util.playerViewModel
import br.com.player.player.PlayerConfig
import br.com.player.player.ui.PlayerIntent
import br.com.player.player.ui.PlayerViewModel
import br.com.player.player.ui.VideoPlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

/**
 * Feed vertical de vídeos.
 *
 * Reaproveita integralmente o módulo de player: o [PlayerViewModel] (com `DefaultPreloadManager`,
 * cache e regras de playback) atua como uma lib, e a [VideoPlayerScreen] renderiza o card ativo.
 * A lista de 30 itens é carregada como playlist (`LoadMediaList`), o que liga o **preload** dos
 * vizinhos; o card mais visível dispara `PlayItemAt`, trocando para a fonte já pré-carregada.
 */
@Composable
fun VideoFeedScreen(
    viewModel: PlayerViewModel = playerViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val feedItems = VideoFeedMock.items

    // Carrega os 30 itens como playlist → ativa o preload (size > 1) e toca o primeiro.
    LaunchedEffect(Unit) {
        viewModel.handleIntent(
            PlayerIntent.LoadMediaList(
                PlayerConfig(mediaList = feedItems.map { it.config })
            )
        )
    }

    // Autoplay: o card de maior área visível vira o item reproduzido.
    LaunchedMostVisible(listState) { index ->
        viewModel.handleIntent(PlayerIntent.PlayItemAt(index))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Lista de Vídeos", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(feedItems, key = { it.position }) { item ->
                val index = item.position - 1
                val isActive = index == uiState.currentIndex
                VideoFeedCard(
                    position = item.position,
                    isActive = isActive,
                    viewModel = viewModel
                )
            }
        }
    }
}

/**
 * Observa o [LazyListState] e reporta o índice do card de maior área visível.
 * O debounce (collectLatest + delay) evita trocar o player a cada item durante um fling.
 */
@Composable
private fun LaunchedMostVisible(
    listState: LazyListState,
    onMostVisible: (Int) -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val viewportStart = info.viewportStartOffset
            val viewportEnd = info.viewportEndOffset
            info.visibleItemsInfo.maxByOrNull { item ->
                val visibleStart = maxOf(item.offset, viewportStart)
                val visibleEnd = minOf(item.offset + item.size, viewportEnd)
                (visibleEnd - visibleStart).coerceAtLeast(0)
            }?.index
        }
            .map { it ?: 0 }
            .distinctUntilChanged()
            .collectLatest { index ->
                delay(250L)
                onMostVisible(index)
            }
    }
}

@Composable
private fun VideoFeedCard(
    position: Int,
    isActive: Boolean,
    viewModel: PlayerViewModel
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Vídeo #$position",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    VideoPlayerScreen(
                        viewModel = viewModel,
                        pauseOnDispose = false
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }
    }
}
