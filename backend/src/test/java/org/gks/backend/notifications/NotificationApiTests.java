package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.notifications.Notifier.Category;
import org.gks.backend.notifications.Notifier.Kind;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.gks.backend.notifications.Notifier.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

/**
 * Bildirim modülü uçtan uca (gerçek yetki kararları; access taklit edilmez): üreticiler iş işlemiyle aynı işlemde
 * yazar, gelen kutusu yalnızca kişinin kendi kayıtlarını gösterir, duyuru kurumsal izin ve MFA'lı web ister.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	Notifier notifier;

	@Autowired
	TransactionTemplate tx;

	TestSessions sessions;
	UUID coordinator;
	String coordinatorWeb;
	UUID operationId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		coordinator = user("Koordinatör");
		coordinatorWeb = sessions.web(coordinator, true);
		operationId = operation("ACTIVE");
		role(coordinator, "KOR", "OPERATION", operationId);
	}

	// ---------------------------------------------------------------- üreticiler

	@Test
	void publishedTaskNotifiesOnlyAssigneesAndInboxLifecycleWorks() throws Exception {
		UUID volunteer = volunteer("Ayşe");
		UUID other = volunteer("Mehmet");
		UUID taskId = publishTask(volunteer);

		String token = sessions.android(volunteer);
		String page = inbox(token).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].category").value("TASK"))
			.andExpect(jsonPath("$.items[0].kind").value("TASK_ASSIGNED"))
			.andExpect(jsonPath("$.items[0].priority").value("HIGH"))
			.andExpect(jsonPath("$.items[0].title").value("Yeni görev: Su dağıtımı"))
			.andExpect(jsonPath("$.items[0].link.type").value("TASK"))
			.andExpect(jsonPath("$.items[0].link.id").value(taskId.toString()))
			.andExpect(jsonPath("$.items[0].operationId").value(operationId.toString()))
			.andExpect(jsonPath("$.items[0].readAt").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(page, "$.items[0].id");
		unread(token, 1);

		assertThat(inboxSize(sessions.android(other))).as("atanmayan gönüllü").isZero();
		assertThat(inboxSize(coordinatorWeb)).as("işlemi yapan kendi eylemi için bildirim almaz").isZero();

		// Ayrıntı ve başkasının kaydı
		mvc.perform(get("/api/v1/me/notifications/{id}", id).with(authenticate(token))).andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("Deprem müdahalesi operasyonunda size görev atandı. Kabul veya reddetmek için açın."));
		mvc.perform(get("/api/v1/me/notifications/{id}", id).with(authenticate(sessions.android(other))))
			.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/me/notifications/{id}/read", id).with(authenticate(sessions.android(other))))
			.andExpect(status().isNotFound());

		// Okundu: ilk okuma read_at'i doldurur, tekrar zararsız
		mvc.perform(post("/api/v1/me/notifications/{id}/read", id).with(authenticate(token))).andExpect(status().isNoContent());
		Instant first = readAt(id);
		mvc.perform(post("/api/v1/me/notifications/{id}/read", id).with(authenticate(token))).andExpect(status().isNoContent());
		assertThat(readAt(id)).isEqualTo(first);
		unread(token, 0);
		inbox(token, "?unreadOnly=true").andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void webSessionAlsoReadsOwnInbox() throws Exception {
		UUID volunteer = volunteer("Zeynep");
		publishTask(volunteer);
		// Kişinin kendi gelen kutusu kurumsal izin değildir: web oturumu da okur (LD-60).
		inbox(sessions.web(volunteer, false)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void failedBusinessTransactionLeavesNoNotification() throws Exception {
		UUID volunteer = volunteer("Ali");
		UUID taskId = draftTask(volunteer);
		// Yanlış sürüm: yayın 409 ile geri alınır → atama da bildirim de yok.
		publish(taskId, 99).andExpect(status().isConflict());
		assertThat(inboxSize(sessions.android(volunteer))).isZero();
		assertThat(count("select count(*) from notifications.notifications where source_id = ?", taskId)).isZero();
	}

	@Test
	void notifierRequiresCallerTransactionAndIgnoresRepeatedSourceEvent() {
		UUID volunteer = user("Can");
		NotificationRequest request = NotificationRequest.toUsers("test:" + UUID.randomUUID(), Category.SYSTEM,
				Kind.GROUP_MEMBER_ADDED, Priority.NORMAL, "Deneme", null, null, null, null, List.of(volunteer), null);

		assertThatThrownBy(() -> notifier.notify(request)).isInstanceOf(IllegalTransactionStateException.class);

		java.util.Optional<UUID> first = tx.execute(s -> notifier.notify(request));
		java.util.Optional<UUID> repeated = tx.execute(s -> notifier.notify(request));
		assertThat(first).isPresent();
		assertThat(repeated).as("aynı kaynak olay anahtarı").isEmpty();
		assertThat(count("select count(*) from notifications.deliveries where user_id = ?", volunteer)).isEqualTo(1);
	}

	@Test
	void cancellationNotifiesActiveAssignees() throws Exception {
		UUID volunteer = volunteer("Elif");
		UUID taskId = publishTask(volunteer);
		mvc.perform(json(post("/api/v1/tasks/{id}/state", taskId), coordinatorWeb, "{\"targetState\": \"CANCELLED\"}"))
			.andExpect(status().isOk());
		inbox(sessions.android(volunteer)).andExpect(jsonPath("$.items[0].kind").value("TASK_CANCELLED"))
			.andExpect(jsonPath("$.items[1].kind").value("TASK_ASSIGNED"));
	}

	@Test
	void meetingPointUpdateNotifiesOtherMembersOnly() throws Exception {
		UUID leader = volunteer("Lider");
		UUID member = volunteer("Üye");
		String body = mvc.perform(json(post("/api/v1/operations/{id}/groups", operationId), coordinatorWeb,
				"{\"name\": \"Arama ekibi\", \"leaderUserId\": \"%s\", \"memberUserIds\": [\"%s\"]}".formatted(leader, member)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String groupId = JsonPath.read(body, "$.id");
		String leaderToken = sessions.android(leader);
		inbox(leaderToken).andExpect(jsonPath("$.items[0].kind").value("GROUP_MEMBER_ADDED"))
			.andExpect(jsonPath("$.items[0].link.type").value("GROUP"));

		mvc.perform(json(put("/api/v1/groups/{id}/meeting-point", groupId), leaderToken,
				"{\"address\": \"Okul bahçesi\", \"time\": \"2026-12-01T07:00:00Z\"}"))
			.andExpect(status().isOk());

		inbox(sessions.android(member)).andExpect(jsonPath("$.items[0].kind").value("MEETING_POINT_UPDATED"))
			.andExpect(jsonPath("$.items[0].body").value("Yeni toplanma noktası: Okul bahçesi"))
			.andExpect(jsonPath("$.items[0].link.id").value(groupId));
		inbox(leaderToken).andExpect(jsonPath("$.items.length()").value(1));   // yalnızca "gruba eklendiniz"
	}

	@Test
	void operationInvitationNotifiesRegisteredUser() throws Exception {
		UUID invited = user("Davetli");
		String email = jdbc.sql("select email from identity.users where id = ?").param(invited).query(String.class).single();
		String body = mvc.perform(json(post("/api/v1/invitations"), coordinatorWeb,
				"{\"email\": \"%s\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(email, operationId))
			.header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		inbox(sessions.android(invited)).andExpect(jsonPath("$.items[0].category").value("OPERATION_INVITE"))
			.andExpect(jsonPath("$.items[0].link.type").value("INVITATION"))
			.andExpect(jsonPath("$.items[0].link.id").value((String) JsonPath.read(body, "$.id")));
	}

	@Test
	void inboxPagesNewestFirst() throws Exception {
		UUID volunteer = user("Sayfa");
		for (int i = 0; i < 3; i++) {
			String key = "test:" + UUID.randomUUID();
			tx.executeWithoutResult(s -> notifier.notify(NotificationRequest.toUsers(key, Category.SYSTEM,
					Kind.GROUP_MEMBER_ADDED, Priority.NORMAL, "Bildirim", null, null, null, null, List.of(volunteer), null)));
		}
		String token = sessions.android(volunteer);
		String first = inbox(token, "?limit=2").andExpect(jsonPath("$.items.length()").value(2))
			.andReturn().getResponse().getContentAsString();
		String cursor = JsonPath.read(first, "$.nextCursor");
		inbox(token, "?limit=2&cursor=" + cursor).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		inbox(token, "?limit=0").andExpect(status().isBadRequest());
		inbox(token, "?category=CHAT").andExpect(status().isBadRequest());
	}

	@Test
	void withoutPushProviderNoPushRowsAreCreated() throws Exception {
		UUID volunteer = volunteer("Cihazlı");
		jdbc.sql("insert into identity.devices (id, user_id, installation_id, fcm_token) values (?, ?, ?, ?)")
			.params(UUID.randomUUID(), volunteer, "kurulum-" + volunteer, "fcm-" + volunteer).update();
		publishTask(volunteer);
		assertThat(count("select count(*) from notifications.deliveries where user_id = ? and channel = 'PUSH'", volunteer))
			.as("sağlayıcı yokken 'gönderildi' kaydı oluşmaz").isZero();
		assertThat(count("select count(*) from notifications.deliveries where user_id = ? and channel = 'IN_APP'", volunteer))
			.isEqualTo(1);
	}

	// ---------------------------------------------------------------- duyurular

	@Nested
	class Announcements {

		@Test
		void coordinatorSendsToWholeOperation() throws Exception {
			UUID a = volunteer("Ahmet");
			UUID b = volunteer("Burak");
			announcementPreview(coordinatorWeb, "{\"operationId\": \"%s\"}".formatted(operationId))
				.andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(2))
				.andExpect(jsonPath("$.confirmationRequired").value(false));
			String body = announce(coordinatorWeb, "{\"operationId\": \"%s\", \"title\": \"Toplantı\", \"body\": \"Saat 18'de çadırda.\"}"
				.formatted(operationId)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.recipientCount").value(2))
				.andExpect(jsonPath("$.delivery.sent").value(2))
				.andReturn().getResponse().getContentAsString();
			String id = JsonPath.read(body, "$.id");

			inbox(sessions.android(a)).andExpect(jsonPath("$.items[0].category").value("ANNOUNCEMENT"))
				.andExpect(jsonPath("$.items[0].body").value("Saat 18'de çadırda."))
				.andExpect(jsonPath("$.items[0].link.type").value("ANNOUNCEMENT"))
				.andExpect(jsonPath("$.items[0].link.id").value(id));
			assertThat(inboxSize(sessions.android(b))).isEqualTo(1);
			assertThat(inboxSize(coordinatorWeb)).as("gönderen alıcı değildir").isZero();

			mvc.perform(get("/api/v1/announcements").param("operationId", operationId.toString()).with(authenticate(coordinatorWeb)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
				.andExpect(jsonPath("$.items[0].body").doesNotExist());
			mvc.perform(get("/api/v1/announcements/{id}", id).with(authenticate(coordinatorWeb))).andExpect(status().isOk())
				.andExpect(jsonPath("$.body").value("Saat 18'de çadırda."));
		}

		@Test
		void groupTargetMustBelongToOperation() throws Exception {
			UUID otherOperation = operation("ACTIVE");
			role(coordinator, "KOR", "OPERATION", otherOperation);
			UUID leader = volunteerOf(otherOperation, "Başka");
			UUID foreignGroup = UUID.randomUUID();
			jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, 'Yabancı', ?)")
				.params(foreignGroup, ORGANIZATION_ID, otherOperation, coordinator).update();
			jdbc.sql("insert into groups.group_memberships (id, group_id, user_id, role, added_by) values (?, ?, ?, 'LEADER', ?)")
				.params(UUID.randomUUID(), foreignGroup, leader, coordinator).update();
			announcementPreview(coordinatorWeb, "{\"operationId\": \"%s\", \"groupIds\": [\"%s\"]}".formatted(operationId, foreignGroup))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}

		@Test
		void authorizationRulesAreKept() throws Exception {
			volunteer("Hedef");
			String create = "{\"operationId\": \"%s\", \"title\": \"Duyuru\", \"body\": \"Metin\"}".formatted(operationId);
			// Android oturumu kurumsal izin taşımaz (ADR-013)
			announce(sessions.android(coordinator), create).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
			// MFA'sız web oturumu: KOR izni MFA ister (LD-01)
			announce(sessions.web(coordinator, false), create).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
			// Başka operasyonun KOR'u değil
			UUID stranger = user("Yabancı KOR");
			role(stranger, "KOR", "OPERATION", operation("ACTIVE"));
			announce(sessions.web(stranger, true), create).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
			// Kurum geneli yalnızca ADM
			announce(coordinatorWeb, "{\"organization\": true, \"title\": \"Duyuru\", \"body\": \"Metin\"}")
				.andExpect(status().isForbidden());
			// Idempotency-Key zorunlu (Z)
			mvc.perform(json(post("/api/v1/announcements"), coordinatorWeb, create)).andExpect(status().isBadRequest());
			assertThat(count("select count(*) from notifications.notifications where operation_id = ?", operationId)).isZero();
		}

		@Test
		void plannedOperationIsRejected() throws Exception {
			UUID planned = operation("PLANNED");
			role(coordinator, "KOR", "OPERATION", planned);
			announcementPreview(coordinatorWeb, "{\"operationId\": \"%s\"}".formatted(planned))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_NOT_ACTIVE"));
		}

		@Test
		void adminSendsOrganizationWideAndRepeatedKeyDoesNotDuplicate() throws Exception {
			UUID admin = user("Yönetici");
			role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
			UUID reader = user("Okur");
			String token = sessions.web(admin, true);
			String key = UUID.randomUUID().toString();
			String create = "{\"organization\": true, \"title\": \"Kurum duyurusu\", \"body\": \"Tatbikat cumartesi.\", \"confirm\": true}";
			String first = mvc.perform(json(post("/api/v1/announcements"), token, create).header("Idempotency-Key", key))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			String second = mvc.perform(json(post("/api/v1/announcements"), token, create).header("Idempotency-Key", key))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
			assertThat(count("""
					select count(*) from notifications.deliveries d join notifications.notifications n on n.id = d.notification_id
					 where d.user_id = ? and n.category = 'ANNOUNCEMENT'""", reader)).isEqualTo(1);
			announce(token, "{\"organization\": true, \"operationId\": \"%s\", \"title\": \"Kurum\", \"body\": \"x\"}".formatted(operationId))
				.andExpect(status().isBadRequest());
		}

		@Test
		void validationAndEmptyAudience() throws Exception {
			announce(coordinatorWeb, "{\"operationId\": \"%s\", \"title\": \"ab\", \"body\": \"\"}".formatted(operationId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"))
				.andExpect(jsonPath("$.errors[1].field").value("body"));
			// Katılımcısı olmayan operasyon
			announce(coordinatorWeb, "{\"operationId\": \"%s\", \"title\": \"Duyuru\", \"body\": \"Metin\"}".formatted(operationId))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].code").value("EMPTY_AUDIENCE"));
		}
	}

	// ---------------------------------------------------------------- yardımcılar

	UUID publishTask(UUID... users) throws Exception {
		UUID taskId = draftTask(users);
		publish(taskId, 1).andExpect(status().isOk());
		return taskId;
	}

	UUID draftTask(UUID... users) throws Exception {
		String ids = String.join(", ", java.util.Arrays.stream(users).map(u -> "\"" + u + "\"").toList());
		String body = mvc.perform(json(post("/api/v1/operations/{id}/tasks", operationId), coordinatorWeb,
				"{\"title\": \"Su dağıtımı\", \"targets\": {\"userIds\": [" + ids + "]}}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	ResultActions publish(UUID taskId, int version) throws Exception {
		return mvc.perform(json(post("/api/v1/tasks/{id}/publish", taskId), coordinatorWeb, "{\"version\": " + version + "}")
			.header("Idempotency-Key", UUID.randomUUID().toString()));
	}

	ResultActions announce(String token, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/announcements"), token, body).header("Idempotency-Key", UUID.randomUUID().toString()));
	}

	ResultActions announcementPreview(String token, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/announcements/preview"), token, body));
	}

	ResultActions inbox(String token) throws Exception {
		return inbox(token, "");
	}

	ResultActions inbox(String token, String query) throws Exception {
		return mvc.perform(get("/api/v1/me/notifications" + query).with(authenticate(token)));
	}

	int inboxSize(String token) throws Exception {
		return JsonPath.read(inbox(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.items.length()");
	}

	void unread(String token, int expected) throws Exception {
		mvc.perform(get("/api/v1/me/notifications/unread-count").with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(expected));
	}

	Instant readAt(String deliveryId) {
		return jdbc.sql("select read_at from notifications.deliveries where id = ?").param(UUID.fromString(deliveryId))
			.query(Timestamp.class).single().toInstant();
	}

	int count(String sql, Object param) {
		return jdbc.sql(sql).param(param).query(Integer.class).single();
	}

	MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
		return request.with(authenticate(token)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	UUID volunteer(String name) {
		return volunteerOf(operationId, name);
	}

	UUID volunteerOf(UUID operation, String name) {
		UUID id = user(name);
		role(id, "GON", "OPERATION", operation);
		return id;
	}

	UUID user(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	UUID operation(String state) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Deprem müdahalesi', ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, state, state.equals("ACTIVE") ? Timestamp.from(Instant.now()) : null, coordinator)
			.update();
		return id;
	}

}
