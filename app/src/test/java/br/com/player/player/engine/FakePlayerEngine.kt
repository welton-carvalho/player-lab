package br.com.player.player.engine

import androidx.media3.common.Player
import br.com.player.player.CacheConfig
import br.com.player.player.MediaItemConfig

/**
 * Test double de [PlayerEngine] — implementação pura Kotlin sem dependências
 * de Android ou Media3 runtime. Permite testes JVM puros do [PlayerViewModel].
 */
class FakePlayerEngine : PlayerEngine {

    private var eventListener: PlayerEventListener? = null

    // ── Estado controlável pelos testes ──────────────────────────────────────

    var fakeIsPlaying: Boolean = false
    var fakeCurrentPosition: Long = 0L
    var fakeDuration: Long = 0L
    var fakeCurrentMediaItemIndex: Int = 0

    // ── Rastreamento de chamadas ─────────────────────────────────────────────

    val loadDirectCalls = mutableListOf<Pair<List<MediaItemConfig>, CacheConfig>>()
    val registerForPreloadCalls = mutableListOf<List<MediaItemConfig>>()
    val playPreloadedAtCalls = mutableListOf<Triple<Int, MediaItemConfig, CacheConfig>>()
    val seekToPositionCalls = mutableListOf<Long>()
    val seekToItemCalls = mutableListOf<Pair<Int, Long>>()

    var playCalled = 0; private set
    var pauseCalled = 0; private set
    var seekToNextCalled = 0; private set
    var seekToPreviousCalled = 0; private set
    var releaseCalled = 0; private set
    var resetPreloadCalled = 0; private set
    var invalidatePreloadCalled = 0; private set
    var currentPreloadIndex = -1; private set

    // ── PlayerEngine impl ────────────────────────────────────────────────────

    override val player: Player
        get() = error("FakePlayerEngine.player não deve ser usado em testes do ViewModel")

    override fun setEventListener(listener: PlayerEventListener) { eventListener = listener }
    override fun clearEventListener() { eventListener = null }

    override val isPlaying: Boolean get() = fakeIsPlaying
    override val currentPosition: Long get() = fakeCurrentPosition
    override val duration: Long get() = fakeDuration
    override val currentMediaItemIndex: Int get() = fakeCurrentMediaItemIndex

    override fun play() { playCalled++; fakeIsPlaying = true }
    override fun pause() { pauseCalled++; fakeIsPlaying = false }
    override fun seekTo(positionMs: Long) { seekToPositionCalls += positionMs }
    override fun seekToItem(index: Int, positionMs: Long) { seekToItemCalls += index to positionMs }
    override fun seekToNext() { seekToNextCalled++; fakeCurrentMediaItemIndex++ }
    override fun seekToPrevious() {
        seekToPreviousCalled++
        if (fakeCurrentMediaItemIndex > 0) fakeCurrentMediaItemIndex--
    }

    override fun loadDirect(items: List<MediaItemConfig>, cacheConfig: CacheConfig) {
        loadDirectCalls += items to cacheConfig
    }

    override fun registerForPreload(items: List<MediaItemConfig>) {
        registerForPreloadCalls += items
    }

    override fun playPreloadedItemAt(index: Int, config: MediaItemConfig, cacheConfig: CacheConfig) {
        playPreloadedAtCalls += Triple(index, config, cacheConfig)
    }

    override fun setCurrentPreloadIndex(index: Int) { currentPreloadIndex = index }
    override fun invalidatePreload() { invalidatePreloadCalled++ }
    override fun resetPreload() { resetPreloadCalled++ }
    override fun release() { releaseCalled++ }

    // ── Helpers para simular eventos do player nos testes ───────────────────

    fun simulateIsPlayingChanged(isPlaying: Boolean) {
        fakeIsPlaying = isPlaying
        eventListener?.onIsPlayingChanged(isPlaying)
    }

    fun simulateBuffering() = eventListener?.onPlaybackStateChanged(isBuffering = true, isEnded = false)
    fun simulateReady() = eventListener?.onPlaybackStateChanged(isBuffering = false, isEnded = false)
    fun simulateEnded() = eventListener?.onPlaybackStateChanged(isBuffering = false, isEnded = true)

    fun simulatePositionChanged(positionMs: Long, durationMs: Long = fakeDuration) {
        fakeCurrentPosition = positionMs
        eventListener?.onPositionChanged(positionMs, durationMs)
    }

    fun simulateMediaItemIndexChanged(index: Int) {
        fakeCurrentMediaItemIndex = index
        eventListener?.onMediaItemIndexChanged(index)
    }
}
