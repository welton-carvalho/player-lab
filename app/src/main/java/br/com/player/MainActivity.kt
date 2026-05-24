package br.com.player

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.player.player.AspectRatioMode
import br.com.player.player.CacheManager
import br.com.player.player.MediaItemConfig
import br.com.player.player.PlayerConfig
import br.com.player.player.ui.PlayerEffect
import br.com.player.player.ui.PlayerIntent
import br.com.player.player.ui.PlayerViewModel
import br.com.player.player.ui.VideoPlayerScreen
import br.com.player.ui.theme.PlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlayerTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            CacheManager.release()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PlayerViewModel = viewModel()) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val uiState by viewModel.uiState.collectAsState()

    var url by remember { mutableStateOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") }
    var applyClip by remember { mutableStateOf(false) }
    var clipDurationMs by remember { mutableStateOf("10000") }
    var isLoading by remember { mutableStateOf(false) }

    // Opções de aspect ratio disponíveis no seletor
    val aspectRatioOptions = remember {
        listOf(
            "FillBounds" to AspectRatioMode.FillBounds,
            "Crop"       to AspectRatioMode.Crop,
            "Inside"     to AspectRatioMode.Inside,
            "16:9"       to AspectRatioMode.RATIO_16_9,
            "4:3"        to AspectRatioMode.RATIO_4_3,
            "1:1"        to AspectRatioMode.RATIO_1_1,
            "9:16"       to AspectRatioMode.RATIO_9_16,
        )
    }
    var aspectRatioMode by remember { mutableStateOf<AspectRatioMode>(AspectRatioMode.FillBounds) }
    var aspectRatioExpanded by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Coleta efeitos do ViewModel para exibir Snackbar
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlayerEffect.ShowErrorToast -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = effect.message,
                            actionLabel = "OK",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
                is PlayerEffect.OnPlaylistEnded -> { /* opcional */ }
            }
        }
    }

    // Reseta o estado de loading quando o player estabiliza
    LaunchedEffect(uiState.isBuffering, uiState.errorMessage) {
        if (!uiState.isBuffering || uiState.errorMessage != null) {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            if (!isLandscape) {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Player",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isLandscape) PaddingValues(0.dp) else innerPadding)
        ) {
            if (!isLandscape) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {

                            // Campo URL com ícone e botão clear
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("URL do Vídeo (HLS/DASH)") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    if (url.isNotEmpty()) {
                                        IconButton(onClick = { url = "" }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = "Limpar URL"
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Switch moderno no lugar do Checkbox
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Aplicar corte de duração",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "Limita o tempo de reprodução",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = applyClip,
                                    onCheckedChange = { applyClip = it }
                                )
                            }

                            // Campo de duração com animação spring
                            AnimatedVisibility(
                                visible = applyClip,
                                enter = slideInVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    initialOffsetY = { -it / 2 }
                                ) + fadeIn(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                                exit = slideOutVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    targetOffsetY = { -it / 2 }
                                ) + fadeOut(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            ) {
                                OutlinedTextField(
                                    value = clipDurationMs,
                                    onValueChange = { clipDurationMs = it },
                                    label = { Text("Duração do corte (ms)") },
                                    supportingText = { Text("Ex: 10000 = 10 segundos") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Timer,
                                            contentDescription = null
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Seletor de Aspect Ratio
                            val selectedLabel = aspectRatioOptions
                                .first { it.second == aspectRatioMode }.first
                            ExposedDropdownMenuBox(
                                expanded = aspectRatioExpanded,
                                onExpandedChange = { aspectRatioExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Proporção (Aspect Ratio)") },
                                    supportingText = { Text("Como o vídeo preenche o player") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectRatioExpanded)
                                    },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium
                                )
                                ExposedDropdownMenu(
                                    expanded = aspectRatioExpanded,
                                    onDismissRequest = { aspectRatioExpanded = false }
                                ) {
                                    aspectRatioOptions.forEach { (label, mode) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                aspectRatioMode = mode
                                                aspectRatioExpanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Botão com estado de loading
                            Button(
                                onClick = {
                                    isLoading = true
                                    val clip = if (applyClip) clipDurationMs.toLongOrNull() else null
                                    val config = PlayerConfig(
                                        mediaList = listOf(
                                            MediaItemConfig(url = url, clipDurationMs = clip)
                                        )
                                    )
                                    viewModel.handleIntent(PlayerIntent.LoadMediaList(config))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = url.isNotEmpty() && !isLoading,
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Carregando...",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Carregar e Reproduzir",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                VideoPlayerScreen(
                    viewModel = viewModel,
                    aspectRatioMode = aspectRatioMode
                )
            }
        }
    }
}
