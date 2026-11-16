package org.gks.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.access.GroupAccess.GroupAction;
import org.gks.backend.access.TaskAccess.TaskAction;
import org.gks.backend.access.TaskAccess.ViewScope;
import org.gks.backend.access.WebAccess.WebLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Gerçek rol atamalarıyla izin tablosu (authorization.md §2, §5; backend §2.4; ADR-014, ADR-015, K-19, LD-01, LD-03,
 * LD-38). Taklit yok: kararlar {@code access.role_assignments} satırlarından hesaplanır.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccessPolicyTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	JdbcClient jdbc;

	@Autowired
	OperationAccess operations;

	@Autowired
	GroupAccess groups;

	@Autowired
	TaskAccess tasks;

	@Autowired
	WebAccess web;

	@Autowired
	Participation participation;

	@Autowired
	AnnouncementAccess announcements;

	@Autowired
	EffectivePermissions permissions;

	UUID operationId;
	UUID otherOperationId;

	@BeforeEach
	void setUp() {
		UUID creator = user();
		operationId = operation(creator);
		otherOperationId = operation(creator);
	}

	static Subject webMfa(UUID userId) {
		return new Subject(userId, true, true);
	}

	static Subject webNoMfa(UUID userId) {
		return new Subject(userId, true, false);
	}

	static Subject android(UUID userId) {
		return new Subject(userId, false, false);
	}

	/** ADR-014 MFA'lı Android yönetim oturumu. */
	static Subject androidMfa(UUID userId) {
		return new Subject(userId, false, true);
	}

	@Nested
	class WebLoginDecision {

		@Test
		void webRoleAndMfaRequirementFollowRoles() {
			UUID nobody = user();
			UUID volunteer = user();
			grant(volunteer, Role.GON, operationId);
			UUID tech = user();
			grant(tech, Role.TEK, ORGANIZATION_ID);
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID adminAuditor = user();
			grant(adminAuditor, Role.ADM, ORGANIZATION_ID);
			grant(adminAuditor, Role.UYM, ORGANIZATION_ID);

			assertThat(web.webLogin(nobody)).isEqualTo(WebLogin.NOT_ALLOWED);
			assertThat(web.webLogin(volunteer)).isEqualTo(WebLogin.NOT_ALLOWED);      // LD-03
			assertThat(web.webLogin(tech)).isEqualTo(WebLogin.ALLOWED);               // LD-01: TEK/UYM MFA'sız
			assertThat(web.webLogin(coordinator)).isEqualTo(WebLogin.MFA_REQUIRED);   // K-19
			assertThat(web.webLogin(adminAuditor)).isEqualTo(WebLogin.MFA_REQUIRED);
		}

	}

	@Nested
	class Operations {

		@Test
		void manageRequiresAdmOrOwnKorOnMfaWebSession() {
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);
			UUID volunteer = user();
			grant(volunteer, Role.GON, operationId);

			assertThat(operations.canManage(webMfa(coordinator), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(operations.canManage(webMfa(coordinator), otherOperationId)).isEqualTo(Decision.FORBIDDEN);
			assertThat(operations.canManage(webNoMfa(coordinator), operationId)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(operations.canManage(android(coordinator), operationId)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(operations.canManage(webMfa(admin), otherOperationId)).isEqualTo(Decision.ALLOW);
			assertThat(operations.canManage(webMfa(volunteer), operationId)).isEqualTo(Decision.FORBIDDEN);
			assertThat(operations.canManage(webMfa(user()), operationId)).isEqualTo(Decision.FORBIDDEN);
		}

		@Test
		void participantViewsOnAnyClientAndCoordinatorOnlyOnMfaWeb() {
			UUID volunteer = user();
			grant(volunteer, Role.GON, operationId);
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);

			assertThat(operations.canView(android(volunteer), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(operations.canView(android(volunteer), otherOperationId)).isEqualTo(Decision.FORBIDDEN);
			assertThat(operations.canView(webMfa(coordinator), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(operations.canView(android(coordinator), operationId)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
		}

		/** AC-05.2: rol kaldırılınca erişim bir sonraki kararda biter (önbellek yok). */
		@Test
		void revokedRoleStopsGrantingImmediately() {
			UUID coordinator = user();
			UUID assignment = grant(coordinator, Role.KOR, operationId);
			assertThat(operations.canManage(webMfa(coordinator), operationId)).isEqualTo(Decision.ALLOW);

			jdbc.sql("update access.role_assignments set revoked_at = now(), revoke_reason = 'MANUAL' where id = ?")
				.param(assignment).update();

			assertThat(operations.canManage(webMfa(coordinator), operationId)).isEqualTo(Decision.FORBIDDEN);
		}

	}

	@Nested
	class Groups {

		/** ADR-015 (K-07'nin yerine): ADM, KOR ataması olmadan her operasyonda grupları yönetir; KOR yalnızca kendi operasyonunda. */
		@Test
		void ownCoordinatorAndAdminManageGroups() {
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);

			for (GroupAction action : new GroupAction[] { GroupAction.CREATE, GroupAction.ADD_MEMBER,
					GroupAction.REMOVE_MEMBER, GroupAction.CHANGE_LEADER, GroupAction.RENAME }) {
				assertThat(groups.can(webMfa(coordinator), action, operationId, null)).isEqualTo(Decision.ALLOW);
				assertThat(groups.can(webMfa(coordinator), action, otherOperationId, null)).isEqualTo(Decision.FORBIDDEN);
				assertThat(groups.can(webMfa(admin), action, operationId, null)).isEqualTo(Decision.ALLOW);
				assertThat(groups.can(webMfa(admin), action, otherOperationId, null)).isEqualTo(Decision.ALLOW);
				assertThat(groups.can(webNoMfa(admin), action, operationId, null)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
				assertThat(groups.can(android(admin), action, operationId, null)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			}
			assertThat(groups.can(webMfa(admin), GroupAction.VIEW, operationId, null)).isEqualTo(Decision.ALLOW);
			assertThat(groups.can(webMfa(admin), GroupAction.UPDATE_MEETING_POINT, otherOperationId, null)).isEqualTo(Decision.ALLOW);
		}

		/** AC-09.6, K-08, LD-38: lider kendi grubunu ve toplanma noktasını günceller (Android'de de); üye ekleyemez. */
		@Test
		void leaderRightsComeFromMembershipRole() {
			UUID leader = user();
			grant(leader, Role.GON, operationId);

			assertThat(groups.can(android(leader), GroupAction.UPDATE, operationId, GroupRole.LEADER)).isEqualTo(Decision.ALLOW);
			assertThat(groups.can(android(leader), GroupAction.UPDATE_MEETING_POINT, operationId, GroupRole.LEADER))
				.isEqualTo(Decision.ALLOW);
			assertThat(groups.can(android(leader), GroupAction.RENAME, operationId, GroupRole.LEADER)).isEqualTo(Decision.FORBIDDEN);
			assertThat(groups.can(android(leader), GroupAction.ADD_MEMBER, operationId, GroupRole.LEADER)).isEqualTo(Decision.FORBIDDEN);
			assertThat(groups.can(android(leader), GroupAction.UPDATE, operationId, GroupRole.MEMBER)).isEqualTo(Decision.FORBIDDEN);
			assertThat(groups.can(android(leader), GroupAction.VIEW, operationId, GroupRole.MEMBER)).isEqualTo(Decision.ALLOW);
			assertThat(groups.can(android(leader), GroupAction.VIEW, operationId, null)).isEqualTo(Decision.FORBIDDEN);
		}

		@Test
		void listScopeUsesCorporateRolesOnlyOnMfaWeb() {
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);

			assertThat(groups.listScope(webMfa(coordinator)).operationIds()).containsExactly(operationId);
			assertThat(groups.listScope(android(coordinator)).operationIds()).isEmpty();
			assertThat(groups.listScope(webMfa(admin)).allOperations()).isTrue();
			assertThat(groups.listScope(webNoMfa(admin)).allOperations()).isFalse();
			assertThat(groups.listScope(android(user())).memberGroups()).isTrue();
		}

	}

	@Nested
	class Tasks {

		@Test
		void manageIsOwnCoordinatorOrAdminAndViewScopeFollowsRole() {
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);
			UUID volunteer = user();
			grant(volunteer, Role.GON, operationId);

			assertThat(tasks.can(webMfa(coordinator), TaskAction.MANAGE, operationId, false)).isEqualTo(Decision.ALLOW);
			assertThat(tasks.can(android(coordinator), TaskAction.MANAGE, operationId, false))
				.isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(tasks.can(webMfa(coordinator), TaskAction.MANAGE, otherOperationId, false)).isEqualTo(Decision.FORBIDDEN);
			assertThat(tasks.can(webMfa(admin), TaskAction.MANAGE, operationId, false)).isEqualTo(Decision.ALLOW);   // ADR-015
			assertThat(tasks.can(webMfa(admin), TaskAction.MANAGE, otherOperationId, false)).isEqualTo(Decision.ALLOW);
			assertThat(tasks.can(android(admin), TaskAction.MANAGE, operationId, false)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(tasks.can(android(volunteer), TaskAction.RESPOND, operationId, true)).isEqualTo(Decision.ALLOW);
			assertThat(tasks.can(android(volunteer), TaskAction.RESPOND, operationId, false)).isEqualTo(Decision.FORBIDDEN);

			assertThat(tasks.viewScope(webMfa(coordinator), operationId)).isEqualTo(ViewScope.ALL);
			assertThat(tasks.viewScope(webMfa(admin), operationId)).isEqualTo(ViewScope.ALL);
			assertThat(tasks.viewScope(android(coordinator), operationId)).isEqualTo(ViewScope.ASSIGNED);   // §2.4
			assertThat(tasks.viewScope(android(volunteer), operationId)).isEqualTo(ViewScope.ASSIGNED);
			assertThat(tasks.viewScope(android(volunteer), otherOperationId)).isEqualTo(ViewScope.NONE);
		}

	}

	@Test
	void participationIsActiveGonAssignment() {
		UUID volunteer = user();
		UUID assignment = grant(volunteer, Role.GON, operationId);
		UUID coordinator = user();
		grant(coordinator, Role.KOR, operationId);

		assertThat(participation.isParticipant(operationId, volunteer)).isTrue();
		assertThat(participation.isParticipant(otherOperationId, volunteer)).isFalse();
		assertThat(participation.isParticipant(operationId, coordinator)).isFalse();
		jdbc.sql("update access.role_assignments set revoked_at = now(), revoke_reason = 'MANUAL' where id = ?")
			.param(assignment).update();
		assertThat(participation.isParticipant(operationId, volunteer)).isFalse();
	}

	/** ADR-015: ADM, KOR ataması olmadan tüm operasyonlarda KOR'un operasyon kapsamlı izinlerine sahiptir. */
	@Nested
	class AdminCoordinatesAllOperations {

		@Test
		void operationAnnouncementIsOwnCoordinatorOrAdmin() {
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);

			assertThat(announcements.canSendToOperation(webMfa(coordinator), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(announcements.canSendToOperation(androidMfa(coordinator), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(announcements.canSendToOperation(webMfa(coordinator), otherOperationId)).isEqualTo(Decision.FORBIDDEN);
			assertThat(announcements.canSendToOperation(webMfa(admin), operationId)).isEqualTo(Decision.ALLOW);
			assertThat(announcements.canSendToOperation(androidMfa(admin), otherOperationId)).isEqualTo(Decision.ALLOW);
			assertThat(announcements.canSendToOperation(android(admin), operationId)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(announcements.canSendToOperation(webNoMfa(admin), operationId)).isEqualTo(Decision.CLIENT_NOT_ALLOWED);
			assertThat(announcements.canSendToOperation(webMfa(user()), operationId)).isEqualTo(Decision.FORBIDDEN);
		}

		@Test
		void effectivePermissionsGiveAdminCoordinatorSetOnEveryOperation() {
			UUID admin = user();
			grant(admin, Role.ADM, ORGANIZATION_ID);
			UUID coordinator = user();
			grant(coordinator, Role.KOR, operationId);

			for (Subject session : new Subject[] { webMfa(admin), androidMfa(admin) }) {
				var view = permissions.of(session, Map.of());
				assertThat(view.operations().get(operationId)).containsAll(EffectivePermissions.ADM_OPERATION)
					.contains("task.manage", "announcement.send.group", "membership.add", "meeting_point.update")
					.contains("alert.send", "alert.cancel", "alert.close", "alert.resend", "alert.view")   // ADR-018
					.doesNotContain("location.view");   // K-15 ayrı karar
				assertThat(view.operations().get(otherOperationId)).containsAll(EffectivePermissions.ADM_OPERATION);
			}
			assertThat(permissions.of(android(admin), Map.of()).operations()).isEmpty();
			assertThat(permissions.of(webNoMfa(admin), Map.of()).operations()).isEmpty();

			var korView = permissions.of(webMfa(coordinator), Map.of());
			assertThat(korView.operations()).containsOnlyKeys(operationId);
		}

	}

	// --- veri ---

	UUID user() {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', 'Test Kişisi', 'Kişi')""").params(id, id + "@example.org").update();
		return id;
	}

	UUID operation(UUID createdBy) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', 'ACTIVE', ?, ?)""")
			.params(id, ORGANIZATION_ID, Timestamp.from(Instant.now()), createdBy).update();
		return id;
	}

	UUID grant(UUID userId, Role role, UUID scopeId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into access.role_assignments (id, user_id, role, scope_type, scope_id)
				values (?, ?, ?, ?, ?)""").params(id, userId, role.name(), role.scopeType.name(), scopeId).update();
		return id;
	}

}
