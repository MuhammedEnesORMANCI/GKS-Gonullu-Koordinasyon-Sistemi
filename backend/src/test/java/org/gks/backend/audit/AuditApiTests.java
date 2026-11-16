package org.gks.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.AuditEvent;
import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Denetim kaydı (K-26, ADR-016) gerçek akışlarla: kayıt iş işlemiyle aynı işlemde yazılır, ADM'nin KOR ataması olmadan
 * yaptığı işlem {@code actor_basis = ADM} olur, içerik yazılmaz, kayıt güncellenemez/silinemez, okuma yalnızca
 * {@code audit.view}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	TransactionTemplate transactions;

	TestSessions sessions;
	UUID admin;
	UUID kor;
	UUID volunteer;
	UUID operationId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		kor = register("Koordinatör");
		volunteer = register("Gönüllü");
		operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', 'ACTIVE', now(), ?)""").params(operationId, ORGANIZATION_ID, admin).update();
		role(kor, "KOR", "OPERATION", operationId);
		role(volunteer, "GON", "OPERATION", operationId);
	}

	@Test
	void taskActionsRecordActingBasis() throws Exception {
		String task = "{\"title\": \"Su dağıtımı\", \"targets\": {\"userIds\": [\"%s\"]}}".formatted(volunteer);
		for (UUID actor : new UUID[] { kor, admin }) {
			mvc.perform(post("/api/v1/operations/{id}/tasks", operationId).with(authenticate(sessions.web(actor, true)))
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(task))
				.andExpect(status().isCreated());
		}
		assertThat(basis("TASK_CREATED", kor)).isEqualTo("KOR");
		assertThat(basis("TASK_CREATED", admin)).as("ADR-015: KOR ataması olmadan").isEqualTo("ADM");
		assertThat(jdbc.sql("select client from audit.audit_entries where action = 'TASK_CREATED' and actor_id = ?")
			.param(admin).query(String.class).single()).isEqualTo("WEB");
	}

	@Test
	void announcementRoleAndAccountEventsCarryNoContent() throws Exception {
		mvc.perform(post("/api/v1/announcements").with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"operationId\": \"%s\", \"title\": \"Gizli başlık\", \"body\": \"Gizli metin\"}".formatted(operationId)))
			.andExpect(status().isAccepted());
		String details = jdbc.sql("select details::text from audit.audit_entries where action = 'ANNOUNCEMENT_SENT' and actor_id = ?")
			.param(admin).query(String.class).single();
		assertThat(details).contains("\"recipientCount\": 1").doesNotContain("Gizli");
		assertThat(basis("ANNOUNCEMENT_SENT", admin)).isEqualTo("ADM");

		UUID other = register("Diğer");
		mvc.perform(post("/api/v1/role-assignments").with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\": \"%s\", \"role\": \"KOR\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(other, operationId)))
			.andExpect(status().isCreated());
		assertThat(jdbc.sql("select count(*) from audit.audit_entries where action = 'ROLE_ASSIGNED' and operation_id = ? and details->>'userId' = ?")
			.params(operationId, other.toString()).query(Integer.class).single()).isEqualTo(1);

		mvc.perform(post("/api/v1/users/{id}/disable", other).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Kayıp telefon\"}")).andExpect(status().isOk());
		String disabled = jdbc.sql("select details::text from audit.audit_entries where action = 'ACCOUNT_DISABLED' and resource_id = ?")
			.param(other).query(String.class).single();
		assertThat(disabled).contains("reasonGiven").doesNotContain("telefon");
	}

	@Test
	void entriesAreAppendOnly() {
		insertDirect();
		for (String sql : new String[] { "update audit.audit_entries set action = 'DEGISTI'", "delete from audit.audit_entries",
				"truncate audit.audit_entries" }) {
			// Spring hatayı sarar; tetikleyicinin iletisi kök nedendedir.
			assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).rootCause().hasMessageContaining("yalnızca eklenebilir");
		}
	}

	@Test
	void failedAuditWriteRollsBackBusinessChangeAndRequiresTransaction() {
		UUID probe = UUID.randomUUID();
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			jdbc.sql("insert into organization.operations (id, organization_id, name, created_by) values (?, ?, 'Geri alınacak', ?)")
				.params(probe, ORGANIZATION_ID, admin).update();
			events.publishEvent(new AuditEvent(admin, "ADM", "WEB", "gecersiz eylem", "OPERATION", probe, probe, Map.of(),
					Instant.now(), null, null));   // ck_audit_entries_action ihlali
		})).isNotNull();
		assertThat(jdbc.sql("select count(*) from organization.operations where id = ?").param(probe).query(Integer.class).single())
			.as("denetim yazılamazsa iş işlemi de geri alınır").isZero();

		assertThatThrownBy(() -> events.publishEvent(new AuditEvent(admin, "ADM", "WEB", "OPERATION_UPDATED", "OPERATION", probe,
				probe, Map.of(), Instant.now(), null, null))).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void readIsLimitedToAuditViewAndFilters() throws Exception {
		mvc.perform(post("/api/v1/operations/{id}/tasks", operationId).with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\": \"Çadır kurulumu\", \"targets\": {\"userIds\": [\"%s\"]}}".formatted(volunteer)))
			.andExpect(status().isCreated());
		UUID uym = register("Uyum");
		role(uym, "UYM", "ORGANIZATION", ORGANIZATION_ID);

		mvc.perform(get("/api/v1/audit-entries").param("operationId", operationId.toString()).param("actorBasis", "ADM")
			.with(authenticate(sessions.web(admin, true))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].action").value("TASK_CREATED"))
			.andExpect(jsonPath("$.items[0].actor.displayName").value("Yönetici"))
			.andExpect(jsonPath("$.items[0].operationName").value("Sel müdahalesi"))
			.andExpect(jsonPath("$.items[0].actorBasis").value("ADM"));
		mvc.perform(get("/api/v1/audit-entries").param("limit", "1").with(authenticate(sessions.web(uym, false))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").isNotEmpty());
		mvc.perform(get("/api/v1/audit-entries").with(authenticate(sessions.web(kor, true)))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/v1/audit-entries").with(authenticate(sessions.web(admin, false)))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
		mvc.perform(get("/api/v1/audit-entries").with(authenticate(sessions.android(admin)))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/audit-entries").param("actorBasis", "HERKES").with(authenticate(sessions.web(admin, true))))
			.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- yardımcılar

	/**
	 * ADR-020: uygulamanın bağlandığı kısıtlı rol (V15, {@code gks_app}) denetim kaydını okuyup ekleyebilir ama
	 * değiştiremez, silemez, boşaltamaz; tetikleyiciyi de kaldıramaz. İş tablolarında tam DML yetkisi vardır.
	 */
	@Test
	void applicationRoleCanOnlyReadAndInsertAuditEntries() {
		insertDirect();
		transactions.executeWithoutResult(status -> {
			jdbc.sql("set local role gks_app").update();
			assertThat(jdbc.sql("select count(*) from audit.audit_entries").query(Integer.class).single()).isPositive();
			insertDirect();
			jdbc.sql("update tasks.tasks set updated_at = updated_at where false").update();
			jdbc.sql("delete from platform.idempotency_keys where false").update();
		});
		for (String sql : new String[] { "update audit.audit_entries set action = 'DEGISTI'", "delete from audit.audit_entries",
				"truncate audit.audit_entries", "alter table audit.audit_entries disable trigger tg_audit_entries_no_update_delete",
				"drop trigger tg_audit_entries_no_truncate on audit.audit_entries" }) {
			assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
				jdbc.sql("set local role gks_app").update();
				jdbc.sql(sql).update();
			})).as(sql).rootCause().hasMessageMatching("(?s).*(permission denied|must be owner|yetki|sahibi).*");
		}
	}

	String basis(String action, UUID actor) {
		return jdbc.sql("select actor_basis from audit.audit_entries where action = ? and actor_id = ? and operation_id = ?")
			.params(action, actor, operationId).query(String.class).single();
	}

	void insertDirect() {
		jdbc.sql("""
				insert into audit.audit_entries (id, actor_id, actor_basis, action, resource_type, client, at)
				values (?, ?, 'ADM', 'OPERATION_UPDATED', 'OPERATION', 'WEB', now())""").params(UUID.randomUUID(), admin).update();
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("denetim-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

}
