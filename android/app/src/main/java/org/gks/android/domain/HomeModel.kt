package org.gks.android.domain

import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.MyGroup
import org.gks.android.data.remote.MyOperation
import org.gks.android.data.remote.PendingInvitation

/** Girişten sonra gösterilecek ana ekran türü; yalnızca `MeContext`'ten türetilir. */
enum class HomeKind {
	/** `mustChangePassword = true`: sunucu diğer uçları `403 AUTH_PASSWORD_CHANGE_REQUIRED` ile reddeder. */
	PASSWORD_CHANGE_REQUIRED,

	/** En az bir aktif/planlı operasyona katılımcı (GON) veya koordinatör (KOR), ya da bir grupta üye/lider. */
	PARTICIPANT,

	/** Yalnızca kurum rolleri (ADM/TEK/UYM): bu roller Android oturumunda izin taşımaz (ADR-013). */
	ORGANIZATION_ONLY,

	/** Henüz bir operasyona katılmamış (ör. kurum daveti kabul edilmiş, operasyon daveti bekleniyor). */
	NO_PARTICIPATION,
}

/** Katılımcı ekranında başlıkta gösterilen rol; en sorumlu olan seçilir. */
enum class PrimaryRole { LEADER, COORDINATOR, VOLUNTEER }

data class HomeModel(
	val kind: HomeKind,
	val displayName: String,
	val primaryRole: PrimaryRole,
	val operations: List<MyOperation>,
	val groups: List<MyGroup>,
	val pendingInvitations: List<PendingInvitation>,
	/** KOR rolü Android'de gönüllü izdüşümüyle çalışır; koordinasyon web panelindedir (backend §2.4). */
	val hasCoordinatorRole: Boolean,
	val operationNames: Map<String, String>,
)

private val ORGANIZATION_ROLES = setOf("ADM", "TEK", "UYM")

fun MeContext.toHomeModel(): HomeModel {
	val isLeader = groups.any { it.role == "LEADER" }
	val isCoordinator = operations.any { "KOR" in it.myRoles }
	val kind = when {
		session.mustChangePassword -> HomeKind.PASSWORD_CHANGE_REQUIRED
		operations.isNotEmpty() || groups.isNotEmpty() -> HomeKind.PARTICIPANT
		roles.any { it.scopeType == "ORGANIZATION" && it.role in ORGANIZATION_ROLES } -> HomeKind.ORGANIZATION_ONLY
		else -> HomeKind.NO_PARTICIPATION
	}
	return HomeModel(
		kind = kind,
		displayName = user.displayName,
		primaryRole = when {
			isLeader -> PrimaryRole.LEADER
			isCoordinator -> PrimaryRole.COORDINATOR
			else -> PrimaryRole.VOLUNTEER
		},
		operations = operations,
		groups = groups,
		pendingInvitations = pendingInvitations,
		hasCoordinatorRole = isCoordinator,
		operationNames = operations.associate { it.id to it.name },
	)
}
