package org.gks.android.data.messaging

import org.gks.android.data.ApiCaller
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.remote.ChannelView
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.MessageView
import org.gks.android.data.remote.MuteRequest
import org.gks.android.data.remote.Page
import org.gks.android.data.remote.ReadRequest
import org.gks.android.data.remote.SendMessageRequest

/** Gönderim sonucu: gönderildi, ağ yok (kuyrukta bekliyor) veya sunucu reddetti (başarısız). */
sealed interface SendOutcome {
	data class Sent(val message: MessageView) : SendOutcome
	data object Queued : SendOutcome
	data class Rejected(val error: ApiError) : SendOutcome
}

/** Mesajlaşma (ADR-024): kanallar, mesajlar ve çevrimdışı kuyruklu gönderim. */
class MessagingRepository(private val api: GksApi, private val caller: ApiCaller, val outbox: MessageOutbox) {

	suspend fun channels(): ApiResult<List<ChannelView>> = caller.call { api.channels() }

	suspend fun messages(channelId: String, cursor: String? = null, limit: Int = 50): ApiResult<Page<MessageView>> =
		caller.call { api.channelMessages(channelId, limit, cursor) }

	suspend fun markRead(channelId: String, messageId: String): ApiResult<Unit> =
		caller.call { api.markChannelRead(channelId, ReadRequest(messageId)) }

	suspend fun mute(channelId: String, muted: Boolean): ApiResult<Unit> = caller.call { api.muteChannel(channelId, MuteRequest(muted)) }

	suspend fun delete(channelId: String, messageId: String): ApiResult<MessageView> = caller.call { api.deleteMessage(channelId, messageId) }

	/** Önce kuyruğa yazılır, sonra gönderilir; ağ yoksa kuyrukta kalır. */
	suspend fun send(channelId: String, body: String): SendOutcome = deliver(outbox.add(channelId, body.trim()))

	/** Bekleyenleri sırayla gönderir; ağ yoksa durur. Gönderilen sayısını döndürür. */
	suspend fun flush(): Int {
		var sent = 0
		for (item in outbox.pending()) {
			when (deliver(item)) {
				is SendOutcome.Sent -> sent++
				SendOutcome.Queued -> return sent
				is SendOutcome.Rejected -> Unit
			}
		}
		return sent
	}

	/** Başarısız mesajı yeniden dener (aynı anahtar). */
	suspend fun retry(key: String): SendOutcome? {
		outbox.markPending(key)
		return outbox.pending().firstOrNull { it.key == key }?.let { deliver(it) }
	}

	private suspend fun deliver(item: PendingMessage): SendOutcome =
		when (val result = caller.call { api.sendMessage(item.channelId, item.key, SendMessageRequest(item.body)) }) {
			is ApiResult.Success -> {
				outbox.remove(item.key)
				SendOutcome.Sent(result.value)
			}
			is ApiResult.Failure -> when (val error = result.error) {
				ApiError.Network, ApiError.RefreshUnavailable -> SendOutcome.Queued
				is ApiError.Http -> if (error.problem.status >= 500 || error.problem.status == 429) SendOutcome.Queued
				else {
					outbox.markFailed(item.key, error.problem.code ?: error.problem.status.toString())
					SendOutcome.Rejected(error)
				}
				else -> SendOutcome.Queued
			}
		}
}
