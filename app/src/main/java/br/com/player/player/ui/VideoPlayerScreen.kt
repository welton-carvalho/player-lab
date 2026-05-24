package br.com.player.player.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import br.com.player.ui.theme.PlayerOverlayBottom
import br.com.player.ui.theme.PlayerOverlayMid
import br.com.player.ui.theme.PlayerOverlayTop
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_TIMEOUT_MS = 3000L

/**
 * Tela composable que hospeda o ContentFrame do Media3 com controles sobrepostos,
 * lógica de auto-ocultação, indicador de carregamento, suporte a tela cheia no
 * modo paisagem e visual cinematográfico com Material 3 Expressive.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    controlsContent: (@Composable (PlayerUiState, Player, (PlayerIntent) -> Unit) -> Unit)? = null
) {
    val uiState = viewModel.uiState.collectAsState()
    val player = viewModel.getPlayer()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current

    var areControlsVisible by remember { mutableStateOf(true) }

    // Auto-ocultar controles após 3s quando estiver reproduzindo
    LaunchedEffect(areControlsVisible, uiState.value.isPlaying) {
        if (areControlsVisible && uiState.value.isPlaying) {
            delay(CONTROLS_HIDE_TIMEOUT_MS)
            areControlsVisible = false
        }
    }

    // Garante que controles aparecem ao entrar em estado de buffering
    LaunchedEffect(uiState.value.isBuffering) {
        if (uiState.value.isBuffering) areControlsVisible = true
    }

    // Lógica de tela cheia para modo paisagem
    LaunchedEffect(configuration.orientation) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { areControlsVisible = !areControlsVisible }
    ) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize()
        )

        // Barra de buffering no topo — menos obstrutiva que spinner centralizado
        if (uiState.value.isBuffering) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        }

        // Overlay com animação spring-based (slide + fade)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = slideInVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                ),
                initialOffsetY = { fullHeight -> fullHeight / 3 }
            ) + fadeIn(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                )
            ),
            exit = slideOutVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                ),
                targetOffsetY = { fullHeight -> fullHeight / 3 }
            ) + fadeOut(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                )
            )
        ) {
            PlayerGradientOverlay(modifier = Modifier.fillMaxSize())

            if (controlsContent == null) {
                DefaultControls(
                    player = player,
                    uiState = uiState.value,
                    onIntent = {
                        viewModel.handleIntent(it)
                        areControlsVisible = true
                    }
                )
            } else {
                controlsContent(uiState.value, player) {
                    viewModel.handleIntent(it)
                    areControlsVisible = true
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.pause()
        }
    }
}

@Composable
private fun PlayerGradientOverlay(modifier: Modifier = Modifier) {
    val gradientBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to PlayerOverlayTop,
                0.35f to PlayerOverlayMid,
                1.0f to PlayerOverlayBottom
            )
        )
    }
    Box(modifier = modifier.background(gradientBrush))
}

@OptIn(UnstableApi::class)
@Composable
private fun DefaultControls(
    player: Player,
    uiState: PlayerUiState,
    onIntent: (PlayerIntent) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Estado de erro com card centralizado
        uiState.errorMessage?.let { error ->
            PlayerErrorCard(
                error = error,
                onRetry = { onIntent(PlayerIntent.RetryLast) },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Controles na parte inferior
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Contador de itens da playlist como chip
            if (uiState.totalItems > 1) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            "${uiState.currentIndex + 1} / ${uiState.totalItems}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        labelColor = Color.White,
                        iconContentColor = Color.White
                    )
                )
            }

            // Slider de progresso + tempo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProgressSlider(
                    player = player,
                    modifier = Modifier.weight(1f),
                    onValueChangeFinished = {
                        onIntent(PlayerIntent.SeekTo(player.currentPosition))
                    }
                )
                PositionAndDurationText(
                    player = player,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Botões de controle com hierarquia visual
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão anterior — menor, tonal
                IconButton(
                    onClick = { onIntent(PlayerIntent.PreviousItem) },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                ) {
                    PreviousButton(player = player, tint = Color.White)
                }

                Spacer(Modifier.width(16.dp))

                // Botão play/pause — maior, destaque com cor primária
                IconButton(
                    onClick = { onIntent(PlayerIntent.TogglePlayPause) },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    PlayPauseButton(player = player, tint = MaterialTheme.colorScheme.onPrimary)
                }

                Spacer(Modifier.width(16.dp))

                // Botão próximo — menor, tonal
                IconButton(
                    onClick = { onIntent(PlayerIntent.NextItem) },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                ) {
                    NextButton(player = player, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlayerErrorCard(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.padding(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Erro",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Tentar novamente")
            }
        }
    }
}
