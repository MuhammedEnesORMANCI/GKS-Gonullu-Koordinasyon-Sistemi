package org.gks.android.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.data.ApiResult

/** Bir ekran verisinin yaşam döngüsü. `refreshing`: veri görünürken arka planda yenileniyor. */
sealed interface Load<out T> {
	data object Loading : Load<Nothing>
	data class Failed(val message: UiText) : Load<Nothing>
	data class Ready<T>(val value: T, val refreshing: Boolean = false, val error: UiText? = null) : Load<T>
}

/**
 * Tek bir kaynağı yükleyen ekranların ortak ViewModel'i: ilk yükleme, yenileme ve hata.
 * Yenileme hatasında eldeki veri korunur, hata bant olarak gösterilir.
 */
abstract class LoadViewModel<T> : ViewModel() {

	private val _state = MutableStateFlow<Load<T>>(Load.Loading)
	val state: StateFlow<Load<T>> = _state.asStateFlow()

	protected abstract suspend fun fetch(): ApiResult<T>

	fun load() {
		val current = _state.value
		if (current is Load.Ready && current.refreshing) return
		_state.value = if (current is Load.Ready) current.copy(refreshing = true, error = null) else Load.Loading
		viewModelScope.launch {
			_state.value = when (val result = fetch()) {
				is ApiResult.Success -> Load.Ready(result.value)
				is ApiResult.Failure -> {
					val message = result.error.toUiText()
					if (current is Load.Ready) current.copy(refreshing = false, error = message) else Load.Failed(message)
				}
			}
		}
	}
	// Not: ilk load() alt sınıfın init bloğunda çağrılır. Burada çağrılsaydı viewModelScope (Main.immediate) onu
	// hemen çalıştırır ve alt sınıfın kurucu alanları (depo) henüz atanmamış olurdu.
}

/** Tek seferlik eylemin (yanıt, kaydetme) durumu: sürüyor, başarı notu veya hata. */
data class ActionState(val busy: Boolean = false, val notice: UiText? = null, val error: UiText? = null)
