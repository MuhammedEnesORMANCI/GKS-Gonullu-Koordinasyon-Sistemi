package org.gks.android.ui.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.PublicAnnouncementRepository
import org.gks.android.data.map
import org.gks.android.data.remote.PublicAnnouncement
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.theme.GksPalette

/** ADR-023: kamu duyuruları (yalnızca yayındakiler). */
class PublicAnnouncementsViewModel(private val repository: PublicAnnouncementRepository) : LoadViewModel<List<PublicAnnouncement>>() {
	init {
		load()
	}

	override suspend fun fetch(): ApiResult<List<PublicAnnouncement>> = repository.page().map { it.items }
}

/**
 * Duyurular sekmesi: kamu duyuruları; operasyon (iç) duyuruları ve kişisel bildirimler "Bildirimlerim"dedir.
 */
@Composable
fun PublicAnnouncementsScreen(viewModel: PublicAnnouncementsViewModel, onOpenInbox: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { items ->
		item {
			OutlinedButton(onClick = onOpenInbox, modifier = Modifier.fillMaxWidth()) {
				Icon(Icons.Filled.Notifications, contentDescription = null)
				Text(stringResource(R.string.announcements_my_inbox), modifier = Modifier.padding(start = 8.dp))
			}
		}
		item { Text(stringResource(R.string.public_announcements_title), style = MaterialTheme.typography.titleLarge) }
		if (items.isEmpty()) item { EmptyView(R.string.public_announcements_empty) }
		items(items, key = { it.id }) { a ->
			Card(Modifier.fillMaxWidth()) {
				Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Icon(Icons.Filled.Notifications, contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(28.dp))
					Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(a.title, style = MaterialTheme.typography.titleMedium)
						Text(formatDateTime(a.publishedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(a.body, style = MaterialTheme.typography.bodyLarge)
					}
				}
			}
		}
	}
}
