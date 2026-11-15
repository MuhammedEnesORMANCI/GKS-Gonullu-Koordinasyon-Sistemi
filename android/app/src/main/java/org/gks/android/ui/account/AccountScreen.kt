package org.gks.android.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.MeContext
import org.gks.android.domain.PanelKind
import org.gks.android.domain.RolePanel
import org.gks.android.domain.rolePanels
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow

@Composable
fun AccountScreen(
	viewModel: AccountViewModel,
	context: MeContext,
	onEditProfile: () -> Unit,
	onChangePassword: () -> Unit,
	onLogout: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	// Sekmeye her girişte (ayrıntı ekranından dönüş dahil) güncel profil okunur.
	LaunchedEffect(Unit) { viewModel.load() }
	LoadContent(state, onRetry = viewModel::load) { profile ->
		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(profile.fullName, style = MaterialTheme.typography.titleLarge)
					Fact(R.string.account_display_name, profile.displayName)
					Fact(R.string.account_email, profile.email)
					Fact(R.string.account_phone, profile.phone ?: stringResource(R.string.account_no_phone))
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						OutlinedButton(onClick = onEditProfile, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.profile_edit)) }
						OutlinedButton(onClick = onChangePassword, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.password_change)) }
					}
				}
			}
		}

		item { SectionTitle(R.string.account_roles_title) }
		val panels = context.rolePanels()
		if (panels.isEmpty()) {
			item { Text(stringResource(R.string.account_no_roles), color = MaterialTheme.colorScheme.onSurfaceVariant) }
		} else {
			items(panels, key = { it.kind.name + it.scopeName }) { RoleCard(it, context.session.mfaVerified) }
		}

		item {
			OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
				Text(stringResource(R.string.action_logout))
			}
		}
	}
}

@Composable
private fun RoleCard(panel: RolePanel, managementSession: Boolean) {
	// ADR-014: ADM/KOR TOTP ile açılan yönetim oturumundaysa kurumsal izinler bu oturumda geçerlidir.
	val managed = managementSession && (panel.kind == PanelKind.ADMIN || panel.kind == PanelKind.COORDINATOR)
	val (title, body) = when (panel.kind) {
		PanelKind.ADMIN -> R.string.role_adm to if (managed) R.string.panel_management_session else R.string.panel_admin
		PanelKind.COORDINATOR -> R.string.role_coordinator to if (managed) R.string.panel_management_session else R.string.panel_coordinator
		PanelKind.SUPPORT -> R.string.role_tek to R.string.panel_support
		PanelKind.COMPLIANCE -> R.string.role_uym to R.string.panel_compliance
		PanelKind.LEADER -> R.string.role_leader to R.string.panel_leader
		PanelKind.VOLUNTEER -> R.string.role_volunteer to R.string.panel_volunteer
	}
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			TitleRow(stringResource(title)) {
				val scheme = MaterialTheme.colorScheme
				if (managed) {
					Badge(stringResource(R.string.panel_management_badge), scheme.tertiaryContainer, scheme.onTertiaryContainer)
				} else if (panel.kind.webOnly) {
					Badge(stringResource(R.string.panel_web_only), scheme.surfaceVariant, scheme.onSurfaceVariant)
				} else {
					Badge(stringResource(R.string.panel_mobile), scheme.primaryContainer, scheme.onPrimaryContainer)
				}
			}
			panel.scopeName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
		}
	}
}
