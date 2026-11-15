package org.gks.android.domain

import org.gks.android.data.remote.ContextSession
import org.gks.android.data.remote.ContextUser
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.MyGroup
import org.gks.android.data.remote.MyOperation
import org.gks.android.data.remote.RoleAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {

	private fun context(
		operations: List<MyOperation> = emptyList(),
		groups: List<MyGroup> = emptyList(),
		roles: List<RoleAssignment> = emptyList(),
		mustChangePassword: Boolean = false,
	) = MeContext(
		user = ContextUser("u1", "Ayşe K."),
		session = ContextSession("s1", "ANDROID", false, "2026-12-15T00:00:00Z", mustChangePassword),
		roles = roles,
		operations = operations,
		groups = groups,
	)

	private val operation = MyOperation("o1", "Sel", "ACTIVE", listOf("GON"))

	@Test
	fun volunteerInOperation() {
		val model = context(operations = listOf(operation), groups = listOf(MyGroup("g1", "Ekip 1", "o1", "MEMBER"))).toHomeModel()
		assertEquals(HomeKind.PARTICIPANT, model.kind)
		assertEquals(PrimaryRole.VOLUNTEER, model.primaryRole)
		assertFalse(model.hasCoordinatorRole)
		assertEquals("Sel", model.operationNames["o1"])
	}

	@Test
	fun groupLeaderIsShownAsLeader() {
		val model = context(operations = listOf(operation), groups = listOf(MyGroup("g1", "Ekip 1", "o1", "LEADER"))).toHomeModel()
		assertEquals(PrimaryRole.LEADER, model.primaryRole)
	}

	@Test
	fun coordinatorGetsVolunteerProjectionWithNote() {
		val model = context(operations = listOf(operation.copy(myRoles = listOf("KOR")))).toHomeModel()
		assertEquals(HomeKind.PARTICIPANT, model.kind)
		assertEquals(PrimaryRole.COORDINATOR, model.primaryRole)
		assertTrue(model.hasCoordinatorRole)
	}

	@Test
	fun organizationRolesOnlyPointToWebPanel() {
		val adm = RoleAssignment("r1", "ADM", "ORGANIZATION", "org")
		assertEquals(HomeKind.ORGANIZATION_ONLY, context(roles = listOf(adm)).toHomeModel().kind)
	}

	@Test
	fun noParticipationYet() {
		assertEquals(HomeKind.NO_PARTICIPATION, context().toHomeModel().kind)
	}

	@Test
	fun rolePanelsMarkCorporateRolesWebOnlyAndAddLeadership() {
		val ctx = context(
			operations = listOf(operation.copy(myRoles = listOf("KOR"))),
			groups = listOf(MyGroup("g1", "Ekip 1", "o1", "LEADER")),
			roles = listOf(
				RoleAssignment("r1", "ADM", "ORGANIZATION", "org", "GKS"),
				RoleAssignment("r2", "KOR", "OPERATION", "o1", "Sel"),
			),
		)
		val panels = ctx.rolePanels()
		assertEquals(listOf(PanelKind.ADMIN, PanelKind.COORDINATOR, PanelKind.LEADER), panels.map { it.kind })
		assertTrue(panels.filter { it.kind != PanelKind.LEADER }.all { it.kind.webOnly })
		assertFalse(PanelKind.LEADER.webOnly)
		assertEquals("Ekip 1", panels.last().scopeName)
	}

	@Test
	fun passwordChangeTakesPrecedence() {
		val model = context(operations = listOf(operation), mustChangePassword = true).toHomeModel()
		assertEquals(HomeKind.PASSWORD_CHANGE_REQUIRED, model.kind)
	}
}
