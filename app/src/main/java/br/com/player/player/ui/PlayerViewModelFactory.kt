package br.com.player.player.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import br.com.player.player.BufferConfig
import br.com.player.player.CacheConfig
import br.com.player.player.engine.ExoPlayerEngine

class PlayerViewModelFactory(
    private val app: Application,
    private val bufferConfig: BufferConfig = BufferConfig(),
    private val cacheConfig: CacheConfig = CacheConfig()
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        PlayerViewModel(ExoPlayerEngine(app, bufferConfig, cacheConfig)) as T
}
