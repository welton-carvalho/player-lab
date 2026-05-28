# Video Preload & Cache — Spec

Implementação das técnicas de preload e cache do Instagram/Reels usando Media3 1.10.1.

---

## Arquitetura geral

```
ReelsScreen / VideoFeedScreen
       ↓
  PlayerViewModel          (MVI — zero imports Android/Media3)
       ↓ via PlayerEngine interface
  ExoPlayerEngine          (Media3 — DefaultPreloadManager + ExoPlayer)
       ├── AdaptivePreloadStatusControl   (policy de preload adaptativa)
       ├── NetworkQualityProvider         (tier de rede do OS)
       ├── BandwidthAdvisor               (tuning de buffers por bandwidth)
       ├── DeviceCapabilityTier           (cap de preload por RAM)
       └── MediaSourceBuilder → CacheManager (SimpleCache 200 MB LRU)
```

---

## Componentes

### `NetworkQualityProvider`
`player/NetworkQualityProvider.kt`

Classifica a conexão ativa em quatro tiers usando `ConnectivityManager`:

| Tier | Critério |
|------|----------|
| `WIFI` | Transport WiFi ou Ethernet |
| `CELLULAR_FAST` | Celular com ≥ 2 Mbps downstream (API 29+) ou rede 4G/HSPAP (API 26-28) |
| `CELLULAR_SLOW` | Celular com < 2 Mbps ou 3G/2G |
| `OFFLINE` | Sem rede ativa |

Consultado a cada troca de item (`setCurrentPreloadIndex`) — chamada barata ao OS, sem requisição de rede.

---

### `PreloadPolicy`
`player/AdaptivePreloadStatusControl.kt`

Define o quanto precarregar por distância do item atual:

| Policy | dist +1 | dist +2 | dist +3/4 | máx distância |
|--------|---------|---------|-----------|---------------|
| `WIFI` | 5 s | 2 s | SOURCE_PREPARED | 4 |
| `CELLULAR_FAST` | 3 s | TRACKS_SELECTED | — | 2 |
| `CELLULAR_SLOW` | TRACKS_SELECTED | — | — | 1 |
| `OFFLINE` | — | — | — | 0 |

Significado dos níveis do Media3:
- `specifiedRangeLoaded(ms)` — baixa N ms de segmentos HLS/DASH para disco/cache
- `TRACKS_SELECTED` — seleciona rendição ABR (requisição de manifesto, sem bytes de vídeo)
- `SOURCE_PREPARED` — busca manifesto apenas
- `NOT_PRELOADED` — item ignorado pelo PreloadManager

---

### `AdaptivePreloadStatusControl`
`player/AdaptivePreloadStatusControl.kt`

Implementa `TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus>`. Injetado no `DefaultPreloadManager.Builder` no lugar da antiga `PlaylistPreloadStatusControl` hardcoded.

Campos `@Volatile` (leitura segura da thread do PreloadManager):
- `currentPlayingIndex` — atualizado a cada `setCurrentPreloadIndex()`
- `policy` — atualizado junto com `currentPlayingIndex` via `NetworkQualityProvider`
- `deviceMaxDistance` — cap fixo definido na inicialização via `DeviceCapabilityTier`
- `forcedIndices` — índices solicitados via `requestPreloadAt()`, recebem tratamento de dist 1 (3 s) independente da posição atual; removidos após `onCompleted`

---

### `BandwidthAdvisor`
`player/BandwidthAdvisor.kt`

Lê `DefaultBandwidthMeter.getSingletonInstance(app).bitrateEstimate` e retorna um `BufferConfig` ajustado. Usa o valor de `base` como *ceiling* — nunca aumenta o que foi configurado (Reels usa `bufferForPlaybackMs = 800 ms` e esse valor é preservado).

| Bandwidth estimado | `bufferForPlaybackMs` | `bufferForPlaybackAfterRebufferMs` | `maxBufferMs` |
|--------------------|----------------------|-------------------------------------|---------------|
| ≤ 0 ou < 500 kbps | min(base, 1 000 ms) | min(base, 2 000 ms) | min(base, 15 s) |
| 500 kbps – 2 Mbps | min(base, 1 500 ms) | min(base, 3 000 ms) | min(base, 25 s) |
| > 2 Mbps | base sem alteração | base sem alteração | base sem alteração |

O singleton `DefaultBandwidthMeter` é atualizado automaticamente por todos os downloads HTTP do ExoPlayer (playback + preload), então a estimativa se afina conforme o usuário assiste.

---

### `DeviceCapabilityTier`
`player/DeviceCapabilityTier.kt`

Avalia disponibilidade de RAM via `ActivityManager.MemoryInfo` uma vez na inicialização do `ExoPlayerEngine`:

| Tier | Critério | Cap de `maxPreloadDistance` |
|------|-----------|-----------------------------|
| `LOW` | `memInfo.lowMemory == true` | 1 item |
| `MID` | `totalMem < 2 GB` | 2 itens |
| `HIGH` | `totalMem ≥ 2 GB` | ilimitado (segue a `PreloadPolicy`) |

---

### `ExoPlayerEngine` — mudanças
`player/engine/ExoPlayerEngine.kt`

- `PlaylistPreloadStatusControl` (inner class hardcoded) substituída por `AdaptivePreloadStatusControl`
- `setCurrentPreloadIndex(index)` atualiza `preloadControl.currentPlayingIndex`, `preloadControl.policy` e `preloadManager.setCurrentPlayingIndex()` nessa ordem, antes do `invalidate()`
- `DefaultLoadControl` constrói com `tunedBufferConfig` (resultado do `BandwidthAdvisor`)
- `PreloadManagerListener.onCompleted(mediaItem)` hookeia o fim do preload de cada item: extrai o índice do `mediaItem.mediaId` (setado como `index.toString()` no `registerForPreload`) e emite `PlayerEventListener.onPreloadCompleted(index)`
- `requestPreloadAt(index)` adiciona o índice a `forcedIndices` e chama `invalidate()`
- `registerForPreload` limpa `forcedIndices` ao resetar o PreloadManager

---

### `PlayerEventListener` — novo callback
`player/engine/PlayerEventListener.kt`

```kotlin
fun onPreloadCompleted(index: Int) = Unit
```

Default body — não quebra implementações existentes nem os 22 testes anteriores.

---

### `PlayerEngine` — novo método
`player/engine/PlayerEngine.kt`

```kotlin
fun requestPreloadAt(index: Int) = Unit  // default no-op
```

Força preload imediato de um item específico independente da posição atual. Útil para pré-aquecer itens antes do usuário chegar neles (ex: jump direto para o item 15 numa lista de 30).

---

### `PlayerUiState` — novos campos
`player/ui/PlayerViewModel.kt`

```kotlin
val isPrefetchEnabled: Boolean = false
val preloadedIndices: Set<Int> = emptySet()
```

- `isPrefetchEnabled`: `true` quando a playlist tem > 1 item e `forcePlaylistMode = false`
- `preloadedIndices`: índices cujo preload foi confirmado por `onCompleted`; limpo a cada nova `LoadMediaList`

---

### `ReelsScreen` — indicador de preload
`reels/ReelsScreen.kt`

`LinearProgressIndicator` branco semitransparente na borda inferior da próxima página. Visível apenas durante swipe parcial e somente quando:
- `uiState.isPrefetchEnabled == true` (preload ativo)
- `page !in uiState.preloadedIndices` (próximo item ainda bufferizando)

> Atualmente não visível pois `ReelsScreen` usa `forcePlaylistMode = true` (bug de swipe em aberto). A infraestrutura está pronta para quando o modo de preload for habilitado nos Reels.

---

## Fluxo completo — swipe no Feed

```
Usuário assiste item 3
  ↓
setCurrentPreloadIndex(3)
  → preloadControl.currentPlayingIndex = 3
  → preloadControl.policy = NetworkQualityProvider.currentTier().toPolicy()
  → preloadManager.setCurrentPlayingIndex(3)
  ↓
preloadManager.invalidate()  (chamado por PlayerViewModel.playItemAt)
  → getTargetPreloadStatus(4) → dist 1 → specifiedRangeLoaded(policy.distance1Ms)
  → getTargetPreloadStatus(5) → dist 2 → specifiedRangeLoaded(policy.distance2Ms)  [se WIFI]
  → getTargetPreloadStatus(2) → dist 1 → specifiedRangeLoaded(policy.distance1Ms)
  → etc.
  ↓
Downloads gravados no SimpleCache 200 MB LRU (via CacheDataSource.Factory injetado no PreloadManager.Builder)
  ↓
PreloadManagerListener.onCompleted(mediaItem) → eventListener.onPreloadCompleted(index)
  ↓
PlayerViewModel: preloadedIndices += index
  ↓
Usuário swipe para item 4
  ↓
playPreloadedItemAt(4): preloadManager.getMediaSource(mediaItem) → retorna fonte já cacheada
  → exoPlayer.setMediaSource(cachedSource) + prepare() → playback quase instantâneo
```

---

## O que não mudou

| Componente | Motivo |
|------------|--------|
| `CacheManager` (SimpleCache 200 MB LRU) | Correto e suficiente |
| `MediaSourceBuilder.createFactory()` | Injetado no PreloadManager — bytes preloadados vão para o mesmo cache do playback |
| Arquitetura MVI — `PlayerViewModel` sem imports Android/Media3 | Invariante de testabilidade |
| `ReelsBufferConfig` (`bufferForPlaybackMs = 800 ms`) | Fast-start dos Reels preservado pelo `BandwidthAdvisor` |
| 22 testes existentes em `PlayerViewModelTest` | Todos passam; novos campos têm default |

---

## Novos testes adicionados

`test/player/ui/PlayerViewModelTest.kt` — 4 casos novos (total: 26):

| Teste | Verifica |
|-------|----------|
| `LoadMediaList com multiplos itens define isPrefetchEnabled true` | Flag de preload no estado |
| `LoadMediaList com 1 item define isPrefetchEnabled false` | Flag de preload no estado |
| `onPreloadCompleted adiciona indice a preloadedIndices` | Propagação do evento |
| `LoadMediaList nova lista limpa preloadedIndices anteriores` | Reset ao recarregar |

---

## Referências

- [Instagram and Facebook deliver instant playback with Media3 PreloadManager](https://android-developers.googleblog.com/2026/03/instagram-and-facebook-deliver-instant.html)
- [Elevating media playback: A deep dive into Media3's PreloadManager](https://android-developers.googleblog.com/2025/09/a-deep-dive-into-media3-preloadmanager.html)
- [Media3 PreloadManager concepts — developer.android.com](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
- [Pre-caching Progressive Streams in ExoPlayer](https://medium.com/google-exoplayer/pre-caching-downloading-progressive-streams-in-exoplayer-3a816c75e8f6)
