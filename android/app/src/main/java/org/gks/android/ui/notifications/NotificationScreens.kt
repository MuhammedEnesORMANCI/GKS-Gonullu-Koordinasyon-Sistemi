package org.gks.android.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.InboxItem
import org.gks.android.data.remote.InboxLink
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve

/** Bildirim türünün kısa etiketi (kategoriye göre). */
@Composable
fun categoryLabel(category: String): String = stringResource(
	when (category) {
		"TASK" -> R.string.notification_category_task
		"ANNOUNCEMENT" -> R.string.notification_category_announcement
		"MEETING_POINT" -> R.string.notification_category_meeting_point
		"OPERATION_INVITE" -> R.string.notification_category_invite
		"ALERT" -> R.string.notification_category_alert
		else -> R.string.notification_category_system
	},
)

@Composable
fun InboxScreen(viewModel: InboxViewModel, onOpen: (InboxItem) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val more by viewModel.more.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::refresh) { first ->
		val shown = more ?: first
		if (shown.items.isEmpty()) {
			item { EmptyView(R.string.inbox_empty_title, R.string.inbox_empty_body) }
			return@LoadContent
		}
		items(shown.items, key = { it.id }) { item -> InboxRow(item) { onOpen(item) } }
		shown.moreError?.let { item { MessageCard(it.resolve(), error = true) } }
		if (shown.nextCursor != null) {
			item {
				Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
					if (shown.loadingMore) {
						CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
					} else {
						OutlinedButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.inbox_load_more)) }
					}
				}
			}
		}
	}
}

@Composable
private fun InboxRow(item: InboxItem, onClick: () -> Unit) {
	val unread = item.readAt == null
	val unreadLabel = stringResource(R.string.inbox_unread)
	ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { if (unread) contentDescription = unreadLabel }) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
			// Okunmamış işareti: renkli nokta + kalın başlık (yalnızca renge dayanmaz).
			Box(
				Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape)
					.background(if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
			)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Badge(categoryLabel(item.category))
					Spacer(Modifier.width(8.dp))
					Text(formatDateTime(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				Text(
					item.title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
				)
				item.body?.let {
					Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

/**
 * Ayrıntı: tam metin ve ilgili kayda geçiş. [onRead] okunmamış sayısını ve listeyi günceller.
 * [onOpenLink] `null` dönerse bağlantı türü bu uygulamada açılamaz (düğme gösterilmez).
 */
@Composable
fun NotificationDetailScreen(
	viewModel: NotificationDetailViewModel,
	onRead: (String) -> Unit,
	linkAction: (InboxLink) -> (() -> Unit)?,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { item ->
		item {
			LaunchedEffect(item.id) { onRead(item.id) }
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Badge(categoryLabel(item.category))
					Spacer(Modifier.width(8.dp))
					Text(formatDateTime(item.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				Text(item.title, style = MaterialTheme.typography.headlineSmall)
			}
		}
		item.body?.takeIf { it.isNotBlank() }?.let { body ->
			item {
				ElevatedCard(Modifier.fillMaxWidth()) {
					Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
				}
			}
		}
		item.link?.let(linkAction)?.let { open ->
			item {
				Spacer(Modifier.height(8.dp))
				Button(onClick = open, modifier = Modifier.fillMaxWidth().height(48.dp)) {
					Text(stringResource(linkLabel(item.link.type)))
				}
			}
		}
	}
}

private fun linkLabel(type: String): Int = when (type) {
	"TASK" -> R.string.notification_open_task
	"GROUP" -> R.string.notification_open_group
	"OPERATION" -> R.string.notification_open_operation
	"INVITATION" -> R.string.notification_open_invitation
	else -> R.string.notification_open
}
