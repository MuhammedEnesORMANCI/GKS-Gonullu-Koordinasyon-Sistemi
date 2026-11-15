package org.gks.android.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.data.ApiResult
import org.gks.android.data.NotificationRepository
import org.gks.android.data.remote.InboxItem
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.toUiText
import java.time.Instant

data class InboxData(
	val items: List<InboxItem>,
	val nextCursor: String?,
	val loadingMore: Boolean = false,
	val moreError: UiText? = null,
)

/** NT-04: ilk sayfa + "daha fazla" (imleç). */
class InboxViewModel(private val repository: NotificationRepository) : LoadViewModel<InboxData>() {

	private val extra = MutableStateFlow<InboxData?>(null)

	init { load() }

	override suspend fun fetch(): ApiResult<InboxData> = when (val page = repository.page(null)) {
		is ApiResult.Success -> ApiResult.Success(InboxData(page.value.items, page.value.nextCursor))
		is ApiResult.Failure -> page
	}

	/** "Daha fazla" ile genişletilmiş liste; `null` iken ekran [state]'teki ilk sayfayı gösterir. */
	val more: StateFlow<InboxData?> = extra.asStateFlow()

	fun loadMore() {
		val current = (state.value as? Load.Ready)?.value ?: return
		val shown = extra.value ?: current
		val cursor = shown.nextCursor ?: return
		if (shown.loadingMore) return
		extra.value = shown.copy(loadingMore = true, moreError = null)
		viewModelScope.launch {
			extra.value = when (val page = repository.page(cursor)) {
				is ApiResult.Success -> InboxData(shown.items + page.value.items, page.value.nextCursor)
				is ApiResult.Failure -> shown.copy(loadingMore = false, moreError = page.error.toUiText())
			}
		}
	}

	/** Yenilemede ek sayfalar bırakılır (en yeni ilk sayfa yeniden okunur). */
	fun refresh() {
		extra.value = null
		load()
	}

	/** Ayrıntıdan dönünce satırın okunmuş görünmesi için (sunucu zaten işaretledi). */
	fun markedRead(id: String) {
		val now = Instant.now().toString()
		extra.update { data ->
			(data ?: (state.value as? Load.Ready)?.value)
				?.let { d -> d.copy(items = d.items.map { if (it.id == id && it.readAt == null) it.copy(readAt = now) else it }) }
		}
	}
}

/** NT-07 + NT-05: ayrıntı açılınca okundu işaretlenir. */
class NotificationDetailViewModel(private val repository: NotificationRepository, private val id: String) :
	LoadViewModel<InboxItem>() {

	init { load() }

	override suspend fun fetch(): ApiResult<InboxItem> {
		val item = repository.item(id)
		if (item is ApiResult.Success && item.value.readAt == null) {
			// İşaretleme başarısız olursa ayrıntı yine gösterilir; sayı bir sonraki yoklamada düzelir.
			repository.markRead(id)
		}
		return item
	}
}

/**
 * Okunmamış sayısı (NT-08). FCM yokken yeni bildirimler, uygulama ön plandayken düzenli yoklamayla fark edilir
 * ([POLL_MILLIS]); yoklama ekran açıkken çalışır, arka planda pil ve veri harcamaz.
 */
class UnreadCountViewModel(private val repository: NotificationRepository) : ViewModel() {

	private val _count = MutableStateFlow(0)
	val count: StateFlow<Int> = _count.asStateFlow()

	fun refresh() {
		viewModelScope.launch {
			// Hata (ağ, oturum yenilenemedi) sayıyı sıfırlamaz; bir sonraki yoklamada yeniden denenir.
			(repository.unreadCount() as? ApiResult.Success)?.let { _count.value = it.value }
		}
	}

	companion object {
		const val POLL_MILLIS = 30_000L
	}
}
