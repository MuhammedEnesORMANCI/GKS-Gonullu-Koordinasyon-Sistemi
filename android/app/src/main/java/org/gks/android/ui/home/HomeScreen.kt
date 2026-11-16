package org.gks.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.MyOperation
import org.gks.android.data.remote.PendingInvitation
import org.gks.android.domain.HomeKind
import org.gks.android.domain.PrimaryRole
import org.gks.android.domain.toHomeModel
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.StatusBadge
import org.gks.android.ui.common.InfoCard
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.groupRoleLabel
import org.gks.android.ui.common.operationStateLabel
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.roleLabel

@Composable
fun HomeTab(
	context: MeContext,
	contextRefreshing: Boolean,
	contextError: UiText?,
	viewModel: HomeViewModel,
	onContextChanged: () -> Unit,
	onOpenOperation: (String) -> Unit,
	onOpenGroup: (String) -> Unit,
	top: @Composable () -> Unit = {},
) {
	val model = context.toHomeModel()
	val groups by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()

	Column(Modifier.fillMaxSize()) {
		if (contextRefreshing || (groups as? Load.Ready)?.refreshing == true) LinearProgressIndicator(Modifier.fillMaxWidth())
		LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
			item { top() }
			item { Greeting(model.displayName, model.kind, model.primaryRole) }
			contextError?.let { item { MessageCard(it.resolve(), error = true) } }
			action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
			action.error?.let { item { MessageCard(it.resolve(), error = true) } }
			// Yönetim oturumunda operasyon yönetimi Yönetim sekmesindedir; not yalnızca gönüllü oturumunda gösterilir.
			if (model.hasCoordinatorRole && !context.session.mfaVerified) item { MessageCard(stringResource(R.string.home_coordinator_note), error = false) }

			if (model.pendingInvitations.isNotEmpty()) {
				item { SectionTitle(R.string.home_invitations) }
				items(model.pendingInvitations, key = { "inv-" + it.id }) { invitation ->
					InvitationCard(
						invitation,
						busy = action.busy,
						onAccept = { viewModel.respond(invitation.id, accept = true, onContextChanged) },
						onDecline = { viewModel.respond(invitation.id, accept = false, onContextChanged) },
					)
				}
			}

			when (model.kind) {
				HomeKind.ORGANIZATION_ONLY -> item { InfoCard(R.string.home_organization_only_title, R.string.home_organization_only_body) }
				HomeKind.NO_PARTICIPATION -> item { InfoCard(R.string.home_no_participation_title, R.string.home_no_participation_body) }
				else -> Unit
			}

			if (model.operations.isNotEmpty()) {
				item { SectionTitle(R.string.home_operations) }
				items(model.operations, key = { "op-" + it.id }) { OperationCard(it) { onOpenOperation(it.id) } }
			}

			if (context.groups.isNotEmpty()) groupSection(groups, viewModel::load, onOpenGroup)
		}
	}
}

@Composable
private fun Greeting(name: String, kind: HomeKind, role: PrimaryRole) {
	Column {
		Text(stringResource(R.string.home_greeting, name), style = MaterialTheme.typography.headlineSmall)
		if (kind == HomeKind.PARTICIPANT) {
			Spacer(Modifier.height(8.dp))
			Badge(
				stringResource(
					when (role) {
						PrimaryRole.LEADER -> R.string.role_leader
						PrimaryRole.COORDINATOR -> R.string.role_coordinator
						PrimaryRole.VOLUNTEER -> R.string.role_volunteer
					},
				),
			)
		}
	}
}

private fun LazyListScope.groupSection(groups: Load<List<GroupSummary>>, onRetry: () -> Unit, onOpenGroup: (String) -> Unit) {
	item { SectionTitle(R.string.home_groups) }
	when (groups) {
		Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
		is Load.Failed -> item {
			Column {
				MessageCard(groups.message.resolve(), error = true)
				TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
			}
		}
		is Load.Ready -> items(groups.value, key = { "gr-" + it.id }) { group -> GroupSummaryCard(group) { onOpenGroup(group.id) } }
	}
}

@Composable
fun OperationCard(operation: MyOperation, onClick: () -> Unit) {
	ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			TitleRow(operation.name) { StatusBadge(StatusKind.OPERATION, operation.state) }
			if (operation.myRoles.isNotEmpty()) {
				Text(
					stringResource(R.string.home_my_roles, operation.myRoles.map { roleLabel(it) }.joinToString(", ")),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** Grup özeti: Ana sayfa ve Grubum sekmesi ortak kullanır. */
@Composable
fun GroupSummaryCard(group: GroupSummary, onClick: () -> Unit) {
	ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			TitleRow(group.name) { Badge(groupRoleLabel(group.myRole)) }
			group.operationName?.let {
				Text(stringResource(R.string.home_group_operation, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Text(
				stringResource(R.string.group_leader_line, group.leader?.displayName ?: "—") + " · " +
					stringResource(R.string.group_member_count, group.memberCount),
				style = MaterialTheme.typography.bodyMedium,
			)
		}
	}
}

@Composable
private fun InvitationCard(invitation: PendingInvitation, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(invitation.scopeName ?: "—", style = MaterialTheme.typography.titleMedium)
			Text(
				stringResource(R.string.home_invitation_expires, formatDateTime(invitation.expiresAt)),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.invitation_accept)) }
				OutlinedButton(onClick = onDecline, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.invitation_decline)) }
			}
		}
	}
}
