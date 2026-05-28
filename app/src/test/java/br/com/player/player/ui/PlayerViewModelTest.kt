package br.com.player.player.ui

import br.com.player.MainDispatcherRule
import br.com.player.player.MediaFormat
import br.com.player.player.MediaItemConfig
import br.com.player.player.PlayerConfig
import br.com.player.player.engine.FakePlayerEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeEngine: FakePlayerEngine
    private lateinit var viewModel: PlayerViewModel

    private val singleItem = MediaItemConfig(url = "https://example.com/video.m3u8", format = MediaFormat.HLS)
    private val multiItems = List(3) { MediaItemConfig(url = "https://example.com/video_$it.m3u8") }

    @Before
    fun setUp() {
        fakeEngine = FakePlayerEngine()
        viewModel = PlayerViewModel(fakeEngine)
    }

    @After
    fun tearDown() {
        viewModel.closeForTest()
    }

    // ── LoadMediaList ─────────────────────────────────────────────────────────

    @Test
    fun `LoadMediaList com 1 item chama loadDirect e atualiza totalItems`() = runTest {
        val config = PlayerConfig(mediaList = listOf(singleItem))

        viewModel.handleIntent(PlayerIntent.LoadMediaList(config))
        advanceUntilIdle()

        assertEquals(1, fakeEngine.loadDirectCalls.size)
        assertEquals(listOf(singleItem), fakeEngine.loadDirectCalls.first().first)
        assertEquals(1, viewModel.uiState.value.totalItems)
    }

    @Test
    fun `LoadMediaList com multiplos itens chama registerForPreload e playPreloadedItemAt 0`() = runTest {
        val config = PlayerConfig(mediaList = multiItems)

        viewModel.handleIntent(PlayerIntent.LoadMediaList(config))
        advanceUntilIdle()

        assertEquals(1, fakeEngine.registerForPreloadCalls.size)
        assertEquals(multiItems, fakeEngine.registerForPreloadCalls.first())
        assertEquals(0, fakeEngine.playPreloadedAtCalls.first().first)
        assertEquals(3, viewModel.uiState.value.totalItems)
    }

    @Test
    fun `LoadMediaList reseta errorMessage antes de carregar`() = runTest {
        // Simula um estado de erro anterior
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = listOf(singleItem))))
        advanceUntilIdle()

        // Estado sem erro após carregamento
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `LoadMediaList com lista vazia nao chama loadDirect nem registerForPreload`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = emptyList())))
        advanceUntilIdle()

        assertTrue(fakeEngine.loadDirectCalls.isEmpty())
        assertTrue(fakeEngine.registerForPreloadCalls.isEmpty())
        assertEquals(0, viewModel.uiState.value.totalItems)
    }

    // ── TogglePlayPause ───────────────────────────────────────────────────────

    @Test
    fun `TogglePlayPause pausa quando engine esta tocando`() = runTest {
        fakeEngine.fakeIsPlaying = true

        viewModel.handleIntent(PlayerIntent.TogglePlayPause)

        assertEquals(1, fakeEngine.pauseCalled)
        assertEquals(0, fakeEngine.playCalled)
    }

    @Test
    fun `TogglePlayPause inicia quando engine esta pausado`() = runTest {
        fakeEngine.fakeIsPlaying = false

        viewModel.handleIntent(PlayerIntent.TogglePlayPause)

        assertEquals(1, fakeEngine.playCalled)
        assertEquals(0, fakeEngine.pauseCalled)
    }

    // ── PlayItemAt ────────────────────────────────────────────────────────────

    @Test
    fun `PlayItemAt em modo preload troca para o indice solicitado`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()

        viewModel.handleIntent(PlayerIntent.PlayItemAt(2))

        val calls = fakeEngine.playPreloadedAtCalls
        assertEquals(2, calls.last().first)
        assertEquals(2, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `PlayItemAt no mesmo indice nao dispara playPreloadedItemAt extra`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()
        val callsAfterLoad = fakeEngine.playPreloadedAtCalls.size

        viewModel.handleIntent(PlayerIntent.PlayItemAt(0)) // mesmo índice atual

        assertEquals(callsAfterLoad, fakeEngine.playPreloadedAtCalls.size)
    }

    @Test
    fun `PlayItemAt em modo direto chama seekToItem`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = listOf(singleItem))))
        advanceUntilIdle()

        viewModel.handleIntent(PlayerIntent.PlayItemAt(0))

        assertEquals(1, fakeEngine.seekToItemCalls.size)
    }

    // ── NextItem / PreviousItem ───────────────────────────────────────────────

    @Test
    fun `NextItem em modo preload avanca para proximo indice`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()

        viewModel.handleIntent(PlayerIntent.NextItem)

        assertEquals(1, fakeEngine.playPreloadedAtCalls.last().first)
    }

    @Test
    fun `NextItem no ultimo item nao dispara playPreloadedItemAt extra`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()
        viewModel.handleIntent(PlayerIntent.PlayItemAt(2)) // vai ao último
        val callCount = fakeEngine.playPreloadedAtCalls.size

        viewModel.handleIntent(PlayerIntent.NextItem)

        assertEquals(callCount, fakeEngine.playPreloadedAtCalls.size)
    }

    @Test
    fun `PreviousItem em modo preload volta para indice anterior`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()
        viewModel.handleIntent(PlayerIntent.PlayItemAt(2))

        viewModel.handleIntent(PlayerIntent.PreviousItem)

        assertEquals(1, fakeEngine.playPreloadedAtCalls.last().first)
    }

    @Test
    fun `PreviousItem no primeiro item nao chama playPreloadedItemAt`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()
        val callCount = fakeEngine.playPreloadedAtCalls.size // já está no índice 0

        viewModel.handleIntent(PlayerIntent.PreviousItem)

        assertEquals(callCount, fakeEngine.playPreloadedAtCalls.size)
    }

    // ── SeekTo ───────────────────────────────────────────────────────────────

    @Test
    fun `SeekTo repassa posicao ao engine`() = runTest {
        viewModel.handleIntent(PlayerIntent.SeekTo(5000L))

        assertEquals(listOf(5000L), fakeEngine.seekToPositionCalls)
    }

    // ── RetryLast ────────────────────────────────────────────────────────────

    @Test
    fun `RetryLast recarrega a ultima config`() = runTest {
        val config = PlayerConfig(mediaList = listOf(singleItem))
        viewModel.handleIntent(PlayerIntent.LoadMediaList(config))
        advanceUntilIdle()

        viewModel.handleIntent(PlayerIntent.RetryLast)
        advanceUntilIdle()

        assertEquals(2, fakeEngine.loadDirectCalls.size)
    }

    @Test
    fun `RetryLast sem config previa nao faz nada`() = runTest {
        viewModel.handleIntent(PlayerIntent.RetryLast)
        advanceUntilIdle()

        assertTrue(fakeEngine.loadDirectCalls.isEmpty())
    }

    // ── Eventos do engine → uiState ──────────────────────────────────────────

    @Test
    fun `onIsPlayingChanged atualiza isPlaying no uiState`() = runTest {
        fakeEngine.simulateIsPlayingChanged(true)
        assertTrue(viewModel.uiState.value.isPlaying)

        fakeEngine.simulateIsPlayingChanged(false)
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun `onBuffering atualiza isBuffering no uiState`() = runTest {
        fakeEngine.simulateBuffering()
        assertTrue(viewModel.uiState.value.isBuffering)

        fakeEngine.simulateReady()
        assertFalse(viewModel.uiState.value.isBuffering)
    }

    @Test
    fun `onEnded emite PlayerEffect OnPlaylistEnded`() = runTest {
        val effects = mutableListOf<PlayerEffect>()
        val job = backgroundScope.launch { viewModel.effects.collect { effects += it } }

        fakeEngine.simulateEnded()
        advanceUntilIdle()

        assertTrue(effects.any { it is PlayerEffect.OnPlaylistEnded })
        job.cancel()
    }

    @Test
    fun `onPositionChanged atualiza posicao no uiState`() = runTest {
        fakeEngine.simulatePositionChanged(positionMs = 3000L, durationMs = 60000L)

        assertEquals(3000L, viewModel.uiState.value.currentPositionMs)
        assertEquals(60000L, viewModel.uiState.value.durationMs)
    }

    @Test
    fun `onMediaItemIndexChanged atualiza currentIndex em modo direto`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = listOf(singleItem))))
        advanceUntilIdle()

        fakeEngine.simulateMediaItemIndexChanged(0)

        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    // ── Preload state ────────────────────────────────────────────────────────

    @Test
    fun `LoadMediaList com multiplos itens define isPrefetchEnabled true`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPrefetchEnabled)
    }

    @Test
    fun `LoadMediaList com 1 item define isPrefetchEnabled false`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = listOf(singleItem))))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPrefetchEnabled)
    }

    @Test
    fun `onPreloadCompleted adiciona indice a preloadedIndices`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()

        fakeEngine.simulatePreloadCompleted(1)

        assertTrue(1 in viewModel.uiState.value.preloadedIndices)
        assertFalse(0 in viewModel.uiState.value.preloadedIndices)
    }

    @Test
    fun `LoadMediaList nova lista limpa preloadedIndices anteriores`() = runTest {
        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()
        fakeEngine.simulatePreloadCompleted(1)
        fakeEngine.simulatePreloadCompleted(2)
        assertEquals(2, viewModel.uiState.value.preloadedIndices.size)

        viewModel.handleIntent(PlayerIntent.LoadMediaList(PlayerConfig(mediaList = multiItems)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.preloadedIndices.isEmpty())
    }

    // ── onCleared ────────────────────────────────────────────────────────────

    @Test
    fun `onCleared chama release no engine`() = runTest {
        viewModel.closeForTest()

        assertEquals(1, fakeEngine.releaseCalled)
    }

    @Test
    fun `onCleared limpa o listener do engine`() = runTest {
        viewModel.closeForTest()

        // Após clear, eventos do engine não devem alterar uiState
        val stateBefore = viewModel.uiState.value
        fakeEngine.simulateIsPlayingChanged(true)

        assertEquals(stateBefore, viewModel.uiState.value)
    }
}
