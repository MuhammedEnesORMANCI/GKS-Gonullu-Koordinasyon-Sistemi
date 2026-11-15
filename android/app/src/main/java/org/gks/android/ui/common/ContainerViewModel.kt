package org.gks.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.gks.android.AppContainer
import org.gks.android.GksApplication

/** Uygulama kapsayıcısından ViewModel: her ekran için ayrı fabrika sınıfı yazılmaz. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
	owner: ViewModelStoreOwner? = null,
	key: String? = null,
	crossinline create: (AppContainer) -> VM,
): VM {
	val container = (LocalContext.current.applicationContext as GksApplication).container
	val factory = viewModelFactory { initializer { create(container) } }
	return if (owner != null) viewModel(viewModelStoreOwner = owner, key = key, factory = factory)
	else viewModel(key = key, factory = factory)
}
