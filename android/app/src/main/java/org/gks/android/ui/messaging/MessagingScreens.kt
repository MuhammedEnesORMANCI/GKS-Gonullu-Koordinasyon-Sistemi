package org.gks.android.ui.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.messaging.MessagingRepository
import org.gks.android.data.messaging.PendingMessage
import org.gks.android.data.messaging.SendOutcome
import org.gks.android.data.remote.ChannelView
import org.gks.android.data.remote.MessageView
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.ErrorView
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.theme.GksPalette

const val MESSAGE_MAX = 2000

@Composable
fun channelTitle(c: ChannelView): String = when (c.kind) {
	"GROUP" -> stringResource(R.string.channel_group, c.groupName.orEmpty())
	"COORDINATION" -> stringResource(R.string.channel_coordination)
	else -> stringResource(R.string.channel_operation)
}

/** Kanal listesi (okunmamış sayılarıyla). */
class ChannelsViewModel(private val repository: MessagingRepository) : LoadViewModel<List<ChannelView>>() {
	init {
		load()
	}

	override suspend fun fetch(): ApiResult<List<ChannelView>> = repository.channels()
}

@Composable
fun ChannelsScreen(viewModel: ChannelsViewModel, onOpen: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { channels ->
		if (channels.isEmpty()) item { EmptyView(R.string.messages_empty) }
		channels.groupBy { it.operationName }.forEach { (operation, list) ->
			item(key = "op-$operation") {
				Text(operation, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
			}
			items(list, key = { it.id }) { channel ->
				Card(Modifier.fillMaxWidth().clickable { onOpen(channel.id) }) {
					Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						Column(Modifier.weight(1f)) {
							Text(channelTitle(channel), style = MaterialTheme.typography.titleMedium)
							val sub = when {
								channel.readOnly -> stringResource(R.string.channel_read_only)
								channel.lastMessageAt != null -> formatDateTime(channel.lastMessageAt)
								else -> null
							}
							if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
						if (channel.muted) Text(stringResource(R.string.channel_muted), style = MaterialTheme.typography.labelMedium)
						if (channel.unread > 0) {
							val label = stringResource(R.string.inbox_unread_count, channel.unread)
							Badge(modifier = Modifier.semantics { contentDescription = label }) { Text(if (channel.unread > 99) "99+" else channel.unread.toString()) }
						}
					}
				}
			}
		}
	}
}

/** Akış bağlı değilken açık sohbetin yoklama aralığı (RAPOR_7 madde 2). */
const val CHAT_POLL_MILLIS = 5_000L

/**
 * Canlı akış (SSE) bağlı değilken [refresh] her [intervalMillis]'de çağrılır; bağlanınca yoklama durur (olaylar
 * yeter). Tünel/vekil akışı geciktirirse ya da akış kopuk kalırsa yeni mesaj yine birkaç saniyede görünür.
 */
suspend fun pollWhileDisconnected(connected: StateFlow<Boolean>, intervalMillis: Long = CHAT_POLL_MILLIS, refresh: () -> Unit) {
	connected.collectLatest { live ->
		while (!live) {
			delay(intervalMillis)
			refresh()
		}
	}
}

/** Sohbet durumu: sunucudaki mesajlar + kuyruktaki (bekleyen/başarısız) mesajlar. */
class ChatViewModel(private val repository: MessagingRepository, val channelId: String) : ViewModel() {
	private val _messages = MutableStateFlow<Load<List<MessageView>>>(Load.Loading)
	val messages: StateFlow<Load<List<MessageView>>> = _messages.asStateFlow()
	private val _channel = MutableStateFlow<ChannelView?>(null)
	val channel: StateFlow<ChannelView?> = _channel.asStateFlow()
	val outbox: StateFlow<List<PendingMessage>> = repository.outbox.items
	private val _notice = MutableStateFlow<String?>(null)
	val notice: StateFlow<String?> = _notice.asStateFlow()

	init {
		refresh()
	}

	fun refresh() {
		viewModelScope.launch {
			repository.flush()
			when (val c = repository.channels()) {
				is ApiResult.Success -> _channel.value = c.value.firstOrNull { it.id == channelId }
				is ApiResult.Failure -> Unit
			}
			when (val page = repository.messages(channelId)) {
				is ApiResult.Success -> {
					val items = page.value.items
					_messages.value = Load.Ready(items)
					items.firstOrNull()?.let { latest -> if ((_channel.value?.unread ?: 0) > 0) repository.markRead(channelId, latest.id) }
				}
				is ApiResult.Failure -> {
					val current = _messages.value
					_messages.value = if (current is Load.Ready) current.copy(error = page.error.toUiText()) else Load.Failed(page.error.toUiText())
				}
			}
		}
	}

	fun send(body: String, onQueued: () -> Unit) {
		viewModelScope.launch {
			when (val outcome = repository.send(channelId, body)) {
				is SendOutcome.Sent -> refresh()
				SendOutcome.Queued -> onQueued()
				is SendOutcome.Rejected -> _notice.value = outcome.error.toString()
			}
		}
	}

	fun retry(key: String) {
		viewModelScope.launch { if (repository.retry(key) is SendOutcome.Sent) refresh() }
	}

	fun delete(messageId: String) {
		viewModelScope.launch { if (repository.delete(channelId, messageId) is ApiResult.Success) refresh() }
	}

	fun toggleMute() {
		val c = _channel.value ?: return
		viewModelScope.launch { if (repository.mute(channelId, !c.muted) is ApiResult.Success) _channel.value = c.copy(muted = !c.muted) }
	}
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, onOpened: (String?) -> Unit) {
	val state by viewModel.messages.collectAsStateWithLifecycle()
	val channel by viewModel.channel.collectAsStateWithLifecycle()
	val outbox by viewModel.outbox.collectAsStateWithLifecycle()
	var text by rememberSaveable { mutableStateOf("") }
	var queuedNote by rememberSaveable { mutableStateOf(false) }
	// Açık kanal için bildirim gösterilmez (ADR-024).
	DisposableEffect(viewModel.channelId) {
		onOpened(viewModel.channelId)
		onDispose { onOpened(null) }
	}
	Column(Modifier.fillMaxSize().imePadding()) {
		channel?.let { c ->
			Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text(channelTitle(c), style = MaterialTheme.typography.titleMedium)
					Text(c.operationName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				TextButton(onClick = viewModel::toggleMute) {
					Icon(Icons.Filled.Notifications, contentDescription = null)
					Text(stringResource(if (c.muted) R.string.channel_unmute else R.string.channel_mute), modifier = Modifier.padding(start = 4.dp))
				}
			}
			HorizontalDivider()
		}
		Box(Modifier.weight(1f)) {
			when (val s = state) {
				Load.Loading -> LoadingView()
				is Load.Failed -> ErrorView(s.message, viewModel::refresh)
				is Load.Ready -> LazyColumn(
					reverseLayout = true,
					contentPadding = PaddingValues(12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					val pending = outbox.filter { it.channelId == viewModel.channelId }.reversed()
					items(pending, key = { "p-" + it.key }) { p -> PendingBubble(p, onRetry = { viewModel.retry(p.key) }) }
					items(s.value, key = { it.id }) { m -> MessageBubble(m, onDelete = { viewModel.delete(m.id) }) }
					if (s.value.isEmpty() && pending.isEmpty()) item { Text(stringResource(R.string.messages_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
				}
			}
		}
		if (queuedNote) Text(stringResource(R.string.message_queued), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
		HorizontalDivider()
		if (channel?.canWrite != false) {
			Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					label = { Text(stringResource(R.string.message_label)) },
					isError = text.length > MESSAGE_MAX,
					supportingText = { Text("${text.length}/$MESSAGE_MAX") },
					maxLines = 5,
					modifier = Modifier.weight(1f),
				)
				IconButton(
					onClick = {
						val body = text.trim()
						if (body.isNotEmpty() && body.length <= MESSAGE_MAX) {
							text = ""
							viewModel.send(body) { queuedNote = true }
						}
					},
					enabled = text.isNotBlank() && text.length <= MESSAGE_MAX,
				) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.message_send)) }
			}
		}
		else {
			Text(
				stringResource(if (channel?.readOnly == true) R.string.channel_read_only_note else R.string.channel_admins_only_note),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(16.dp),
			)
		}
	}
}

@Composable
private fun MessageBubble(m: MessageView, onDelete: () -> Unit) {
	if (m.kind == "SYSTEM") {
		Card(Modifier.fillMaxWidth()) {
			Column(Modifier.background(GksPalette.DangerSoft).padding(12.dp)) {
				Text(stringResource(R.string.message_system) + " · " + formatDateTime(m.createdAt), style = MaterialTheme.typography.labelMedium, color = GksPalette.Danger)
				Text(m.body.orEmpty(), style = MaterialTheme.typography.bodyLarge)
			}
		}
		return
	}
	val text = when (m.state) {
		"HIDDEN" -> stringResource(R.string.message_hidden)
		"DELETED" -> stringResource(R.string.message_deleted)
		else -> m.body.orEmpty()
	}
	Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
		Text((if (m.mine) stringResource(R.string.message_me) else m.sender?.displayName.orEmpty()) + " · " + formatDateTime(m.createdAt),
			style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Box(Modifier.widthIn(max = 320.dp).background(if (m.mine) GksPalette.OrangeSoft else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(10.dp)) {
			Text(text, style = MaterialTheme.typography.bodyLarge, fontStyle = if (m.state == "VISIBLE") FontStyle.Normal else FontStyle.Italic)
		}
		if (m.deletable) TextButton(onClick = onDelete) { Text(stringResource(R.string.message_delete)) }
	}
}

@Composable
private fun PendingBubble(p: PendingMessage, onRetry: () -> Unit) {
	Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
		Text(stringResource(if (p.failed) R.string.message_failed else R.string.message_pending), style = MaterialTheme.typography.labelSmall,
			color = if (p.failed) GksPalette.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
		Box(Modifier.widthIn(max = 320.dp).background(GksPalette.OrangeSoft.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(10.dp)) {
			Text(p.body, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
		}
		if (p.failed) TextButton(onClick = onRetry) {
			Icon(Icons.Filled.Refresh, contentDescription = null)
			Text(stringResource(R.string.message_retry))
		}
	}
}
