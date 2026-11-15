package org.gks.android.domain

import org.gks.android.data.remote.MeContext

/**
 * Hesap sekmesindeki rol paneli satırı. Android oturumu kurumsal (ADM/KOR/TEK/UYM) izin taşımaz
 * (ADR-013; backend `Grants.corporate` → `403 CLIENT_NOT_ALLOWED`); bu roller için yalnızca bilgi ve web yönlendirmesi
 * gösterilir. Mobilde yapılabilenler GON (görev/davet yanıtı) ve grup lideridir (açıklama, toplanma noktası).
 */
data class RolePanel(val kind: PanelKind, val scopeName: String?)

enum class PanelKind(val webOnly: Boolean) {
	ADMIN(webOnly = true),
	COORDINATOR(webOnly = true),
	SUPPORT(webOnly = true),
	COMPLIANCE(webOnly = true),
	LEADER(webOnly = false),
	VOLUNTEER(webOnly = false),
}

fun MeContext.rolePanels(): List<RolePanel> {
	val fromRoles = roles.mapNotNull { assignment ->
		val kind = when (assignment.role) {
			"ADM" -> PanelKind.ADMIN
			"KOR" -> PanelKind.COORDINATOR
			"TEK" -> PanelKind.SUPPORT
			"UYM" -> PanelKind.COMPLIANCE
			"GON" -> PanelKind.VOLUNTEER
			else -> null
		}
		kind?.let { RolePanel(it, assignment.scopeName) }
	}
	val leaders = groups.filter { it.role == "LEADER" }.map { RolePanel(PanelKind.LEADER, it.name) }
	return (fromRoles + leaders).sortedBy { it.kind.ordinal }
}
