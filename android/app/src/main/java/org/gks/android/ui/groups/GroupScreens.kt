package org.gks.android.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.gks.android.R
import org.gks.android.data.remote.GroupMember
import org.gks.android.data.remote.MeetingPoint
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.DateInput
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.groupRoleLabel
import org.gks.android.ui.common.resolve
import org.gks.android.ui.home.GroupSummaryCard
import org.gks.android.ui.map.MeetingPointMap

@Composable
fun GroupsScreen(viewModel: GroupsViewModel, onOpenGroup: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { groups ->
		if (groups.isEmpty()) {
			item { EmptyView(R.string.groups_empty_title, R.string.groups_empty_body) }
		} else {
			items(groups, key = { it.id }) { group -> GroupSummaryCard(group) { onOpenGroup(group.id) } }
		}
	}
}

private enum class Editor { DESCRIPTION, MEETING_POINT }

@Composable
fun GroupDetailScreen(viewModel: GroupDetailViewModel) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	var editor by remember { mutableStateOf<Editor?>(null) }

	LoadContent(state, onRetry = viewModel::load) { group ->
		val canEdit = group.myRole == "LEADER" && !group.archived
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				TitleRow(group.name) { Badge(groupRoleLabel(group.myRole)) }
				group.operationName?.let { Text(stringResource(R.string.home_group_operation, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
				if (group.archived) Badge(stringResource(R.string.group_archived))
			}
		}
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		if (canEdit) item { MessageCard(stringResource(R.string.group_leader_note), error = false) }

		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Fact(R.string.group_leader, group.leader?.displayName ?: "—")
					Fact(R.string.group_description, group.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.group_no_description))
					if (canEdit) {
						OutlinedButton(onClick = { editor = Editor.DESCRIPTION }, enabled = !action.busy) {
							Text(stringResource(R.string.group_edit_description))
						}
					}
				}
			}
		}

		item { SectionTitle(R.string.meeting_point_title) }
		item { MeetingPointCard(group.meetingPoint, canEdit, action.busy) { editor = Editor.MEETING_POINT } }

		item { SectionTitle(R.string.group_members_title) }
		val members = group.members.sortedWith(compareBy<GroupMember> { it.role != "LEADER" }.thenBy { it.user.displayName })
		items(members, key = { it.user.id ?: it.user.displayName }) { member ->
			ElevatedCard(Modifier.fillMaxWidth()) {
				Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
					TitleRow(member.user.displayName) {
						if (member.role == "LEADER") {
							Badge(groupRoleLabel(member.role), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
						} else {
							Badge(groupRoleLabel(member.role))
						}
					}
				}
			}
		}
	}

	val group = (state as? Load.Ready)?.value
	when {
		group == null -> Unit
		editor == Editor.DESCRIPTION -> DescriptionDialog(group.description.orEmpty(), onDismiss = { editor = null }) { text ->
			viewModel.saveDescription(text).also { if (it == null) editor = null }
		}
		editor == Editor.MEETING_POINT -> MeetingPointDialog(group.meetingPoint, onDismiss = { editor = null }) { address, description, time ->
			viewModel.saveMeetingPoint(address, description, time).also { if (it == null) editor = null }
		}
	}
}

@Composable
internal fun MeetingPointCard(point: MeetingPoint?, canEdit: Boolean, busy: Boolean, onEdit: () -> Unit) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			if (point == null || (point.address == null && point.lat == null)) {
				Text(stringResource(R.string.meeting_point_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
			} else {
				point.address?.let { Fact(R.string.meeting_point_address, it) }
				if (point.lat != null && point.lon != null) {
					MeetingPointMap(point.lat, point.lon, point.address ?: stringResource(R.string.meeting_point_title))
				}
				point.time?.let { Fact(R.string.meeting_point_time, formatDateTime(it)) }
				point.description?.takeIf { it.isNotBlank() }?.let { Fact(R.string.meeting_point_description, it) }
				point.updatedBy?.let {
					Text(
						stringResource(R.string.meeting_point_updated, it.displayName, formatDateTime(point.updatedAt)),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (canEdit) {
				OutlinedButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.meeting_point_edit)) }
			}
		}
	}
}

/** [onSave] yerel doğrulama hatası döndürürse pencere açık kalır ve hata gösterilir. */
@Composable
private fun DescriptionDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> UiText?) {
	var text by remember { mutableStateOf(initial) }
	var error by remember { mutableStateOf<UiText?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.group_edit_description)) },
		text = {
			OutlinedTextField(
				value = text,
				onValueChange = { text = it; error = null },
				label = { Text(stringResource(R.string.group_description)) },
				isError = error != null,
				supportingText = { Text(error?.resolve() ?: stringResource(R.string.group_description_help)) },
				minLines = 3,
				modifier = Modifier.fillMaxWidth(),
			)
		},
		confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text(stringResource(R.string.action_save)) } },
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

@Composable
internal fun MeetingPointDialog(initial: MeetingPoint?, onDismiss: () -> Unit, onSave: (String, String, String) -> UiText?) {
	var address by remember { mutableStateOf(initial?.address.orEmpty()) }
	var description by remember { mutableStateOf(initial?.description.orEmpty()) }
	var time by remember { mutableStateOf(defaultTime(initial?.time)) }
	var error by remember { mutableStateOf<UiText?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.meeting_point_edit)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(address, { address = it; error = null }, label = { Text(stringResource(R.string.meeting_point_address)) }, modifier = Modifier.fillMaxWidth())
				OutlinedTextField(
					time, { time = it; error = null },
					label = { Text(stringResource(R.string.meeting_point_time)) },
					supportingText = { Text(stringResource(R.string.meeting_point_time_help)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(description, { description = it; error = null }, label = { Text(stringResource(R.string.meeting_point_description)) }, modifier = Modifier.fillMaxWidth())
				error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
				Text(stringResource(R.string.meeting_point_notify), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		},
		confirmButton = { TextButton(onClick = { error = onSave(address, description, time) }) { Text(stringResource(R.string.action_save)) } },
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

/** Mevcut buluşma zamanı; yoksa bir sonraki tam saat. */
private fun defaultTime(iso: String?): String {
	val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
	return DateInput.format(instant)
}
