package br.com.player.util

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Obtém um [ViewModel] garantindo o `APPLICATION_KEY` nas [CreationExtras].
 *
 * Necessário para `AndroidViewModel`s escopados a um `NavEntry` do Navigation 3: o
 * `ViewModelStoreOwner` do entry não fornece a [Application] por padrão, o que faria a
 * `AndroidViewModelFactory` lançar "CreationExtras must have an application by APPLICATION_KEY".
 * Preserva as extras padrão do owner (ex.: saved state) e apenas adiciona a Application.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(): VM {
    val app = LocalContext.current.applicationContext as Application
    val owner = LocalViewModelStoreOwner.current
    val base = (owner as? HasDefaultViewModelProviderFactory)
        ?.defaultViewModelCreationExtras ?: CreationExtras.Empty
    val extras = MutableCreationExtras(base).apply {
        set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
    }
    return viewModel(extras = extras)
}
