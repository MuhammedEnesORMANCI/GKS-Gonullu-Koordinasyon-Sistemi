package org.gks.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.MeContext
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.StatusBadge
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.groupRoleLabel
import org.gks.android.ui.common.operationStateLabel
import org.gks.android.ui.common.roleLabel
import org.gks.android.ui.tasks.TaskCard

@Composable
fun OperationDetailScreen(
	viewModel: OperationDetailViewModel,
	context: MeContext,
	onOpenTask: (String) -> Unit,
	onOpenGroup: (String) -> Unit,
	/** AND-06: katılımcı ve aktif operasyonda konum paylaşımı kartı. */
	locationCard: (@Composable () -> Unit)? = null,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { detail ->
		val operation = detail.operation
		val myRoles = context.operations.firstOrNull { it.id == operation.id }?.myRoles.orEmpty()
		val myGroups = context.groups.filter { it.operationId == operation.id }

		item {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				TitleRow(operation.name) { StatusBadge(StatusKind.OPERATION, operation.state) }
				if (myRoles.isNotEmpty()) {
					Text(
						stringResource(R.string.home_my_roles, myRoles.map { roleLabel(it) }.joinToString(", ")),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
		if ("KOR" in myRoles && !context.session.mfaVerified) item { MessageCard(stringResource(R.string.home_coordinator_note), error = false) }
		if (locationCard != null && "GON" in myRoles && operation.state == "ACTIVE") item { locationCard() }
		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					operation.description?.takeIf { it.isNotBlank() }?.let { Fact(R.string.operation_description, it) }
					operation.plannedEndAt?.let { Fact(R.string.operation_planned_end, formatDateTime(it)) }
					if (operation.coordinators.isNotEmpty()) {
						Fact(R.string.operation_coordinators, operation.coordinators.joinToString(", ") { it.displayName })
					}
				}
			}
		}

		if (myGroups.isNotEmpty()) {
			item { SectionTitle(R.string.home_groups) }
			items(myGroups, key = { "gr-" + it.id }) { group ->
				ElevatedCard(onClick = { onOpenGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
					Column(Modifier.padding(16.dp)) { TitleRow(group.name) { Badge(groupRoleLabel(group.role)) } }
				}
			}
		}

		item { SectionTitle(R.string.operation_my_tasks) }
		if (detail.tasks.isEmpty()) {
			item { EmptyView(R.string.operation_no_tasks) }
		} else {
			items(detail.tasks, key = { "task-" + it.id }) { task ->
				TaskCard(task, task.myAssignment, showOperation = false, onClick = { onOpenTask(task.id) })
			}
		}
	}
}
