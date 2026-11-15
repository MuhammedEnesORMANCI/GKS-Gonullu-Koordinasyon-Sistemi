package org.gks.android.domain

import org.gks.android.data.remote.MeContext

/**
 * Yönetim arayüzünün görünürlüğü (ADR-014). Kaynak, sunucunun **bu oturum için** hesapladığı etkin izinlerdir
 * (`MeContext.permissions`): MFA'sız gönüllü oturumunda kurumsal izin hiç gelmez, dolayısıyla Yönetim sekmesi de
 * görünmez. Bu yalnızca menü kararıdır; her işlemde sunucu yeniden denetler.
 */
data class ManagementAccess(
	val canCreateOperation: Boolean,
	val canAssignRoles: Boolean,
	private val organizationWide: Boolean,
	private val managedOperations: Set<String>,
	/** Grup kurma ve üyelik (GR-01, GR-04 – GR-06) yalnızca operasyonun KOR'una açıktır; ADM grupları salt-okur görür. */
	private val groupManagedOperations: Set<String> = emptySet(),
	/** Görev yönetimi (TK-01 – TK-06) yalnızca K1 KOR (`task.manage`); ADM görevleri salt-okur görür (TK-07). */
	private val taskManagedOperations: Set<String> = emptySet(),
) {
	fun canManageTasks(operationId: String): Boolean = operationId in taskManagedOperations

	fun canManage(operationId: String): Boolean = organizationWide || operationId in managedOperations

	fun canManageGroups(operationId: String): Boolean = operationId in groupManagedOperations

	val isAdmin: Boolean get() = canCreateOperation
}

/** @return yönetim oturumu değilse veya yönetilecek operasyon izni yoksa `null` (sekme gösterilmez) */
fun MeContext.managementAccess(): ManagementAccess? {
	if (!session.mfaVerified) return null
	val organization = permissions.organization
	val managed = permissions.operations.filterValues { OPERATION_MANAGE in it }.keys
	val organizationWide = OPERATION_MANAGE in organization
	if (!organizationWide && managed.isEmpty() && OPERATION_CREATE !in organization) return null
	return ManagementAccess(
		canCreateOperation = OPERATION_CREATE in organization,
		canAssignRoles = ROLE_ASSIGN in organization,
		organizationWide = organizationWide,
		managedOperations = managed,
		groupManagedOperations = permissions.operations.filterValues { GROUP_CREATE in it && MEMBERSHIP_ADD in it }.keys,
		taskManagedOperations = permissions.operations.filterValues { TASK_MANAGE in it }.keys,
	)
}

private const val OPERATION_MANAGE = "operation.manage"
private const val OPERATION_CREATE = "operation.create"
private const val ROLE_ASSIGN = "role.assign"
private const val GROUP_CREATE = "group.create.operational"
private const val MEMBERSHIP_ADD = "membership.add"
private const val TASK_MANAGE = "task.manage"

/**
 * §7.2 geçiş tablosu (Çekirdek: `SUSPENDED` yok). İstemci yalnızca anlamlı düğmeleri göstermek için kullanır;
 * kural sunucudadır (yanlış geçiş `409 INVALID_STATE_TRANSITION`).
 */
fun nextOperationStates(state: String): List<String> = when (state) {
	"PLANNED" -> listOf("ACTIVE", "CANCELLED")
	"ACTIVE" -> listOf("COMPLETED", "CANCELLED")
	"SUSPENDED" -> listOf("ACTIVE", "COMPLETED", "CANCELLED")
	else -> emptyList()
}
