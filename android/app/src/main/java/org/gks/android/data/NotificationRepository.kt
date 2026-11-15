package org.gks.android.data

import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.InboxItem
import org.gks.android.data.remote.Page

/**
 * Uygulama içi gelen kutusu (TD-13). FCM henüz yok: yeni bildirimler okunmamış sayısının yoklanmasıyla fark
 * edilir (uygulama ön plandayken); push eklenince aynı uçlar kullanılmaya devam eder.
 */
class NotificationRepository(private val api: GksApi, private val caller: ApiCaller) {

	suspend fun page(cursor: String?): ApiResult<Page<InboxItem>> = caller.call { api.inbox(cursor) }

	suspend fun item(id: String): ApiResult<InboxItem> = caller.call { api.inboxItem(id) }

	suspend fun markRead(id: String): ApiResult<Unit> = caller.call { api.markRead(id) }

	suspend fun unreadCount(): ApiResult<Int> = caller.call { api.unreadCount() }.map { it.unreadCount }
}
