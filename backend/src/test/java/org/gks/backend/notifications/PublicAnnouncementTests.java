package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-023 kamu duyuruları uçtan uca (gerçek yetki kararları): ADM yönetir ve her değişiklik denetime yazılır; halka
 * açık uç kimlik doğrulamasızdır, yalnızca yayındaki kayıtların dört alanını döndürür, sayfalıdır ve oran sınırlıdır.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "gks.public.rate-limit-per-minute=5")
@AutoConfigureMockMvc
class PublicAnnouncementTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	TestSessions sessions;
	UUID admin;
	String adminWeb;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = user("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		adminWeb = sessions.web(admin, true);
		jdbc.sql("update notifications.public_announcements set state = 'DRAFT', published_at = null").update();
	}

	@Test
	void adminLifecycleIsAuditedAndPublicSeesOnlyPublishedFields() throws Exception {
		String created = mvc.perform(json(post("/api/v1/public-announcements"), adminWeb,
				"{\"title\":\"  Hazırlık haftası  \",\"body\":\"Acil durum çantanızı gözden geçirin.\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.state").value("DRAFT"))
			.andExpect(jsonPath("$.title").value("Hazırlık haftası"))
			.andExpect(jsonPath("$.version").value(1))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		assertThat(publicTitles(ip("10.1.0.1"))).doesNotContain("Hazırlık haftası");

		mvc.perform(json(post("/api/v1/public-announcements/" + id + "/publish"), adminWeb, "{\"version\":1}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").exists());
		mvc.perform(get("/api/v1/public/announcements").with(ip("10.1.0.2")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].title").value("Hazırlık haftası"))
			.andExpect(jsonPath("$.items[0].publishedAt").exists())
			.andExpect(jsonPath("$.items[0].state").doesNotExist())
			.andExpect(jsonPath("$.items[0].version").doesNotExist())
			.andExpect(jsonPath("$.items[0].createdBy").doesNotExist());

		mvc.perform(json(patch("/api/v1/public-announcements/" + id), adminWeb,
				"{\"title\":\"Hazırlık haftası (güncel)\",\"body\":\"Yeni metin\",\"version\":2}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.state").value("PUBLISHED"));
		assertThat(publicTitles(ip("10.1.0.3"))).contains("Hazırlık haftası (güncel)");

		mvc.perform(json(post("/api/v1/public-announcements/" + id + "/unpublish"), adminWeb, "{\"version\":3}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DRAFT")).andExpect(jsonPath("$.publishedAt").doesNotExist());
		assertThat(publicTitles(ip("10.1.0.4"))).doesNotContain("Hazırlık haftası (güncel)");

		mvc.perform(get("/api/v1/public-announcements").with(authenticate(adminWeb)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));

		List<String> actions = jdbc.sql("""
				select action from audit.audit_entries where resource_type = 'PUBLIC_ANNOUNCEMENT' and resource_id = ?::uuid
				order by id""").param(id).query(String.class).list();
		assertThat(actions).containsExactly("PUBLIC_ANNOUNCEMENT_CREATED", "PUBLIC_ANNOUNCEMENT_PUBLISHED",
				"PUBLIC_ANNOUNCEMENT_UPDATED", "PUBLIC_ANNOUNCEMENT_UNPUBLISHED");
		String details = jdbc.sql("select string_agg(details::text, ' ') from audit.audit_entries where resource_id = ?::uuid")
			.param(id).query(String.class).single();
		assertThat(details).doesNotContain("Hazırlık").doesNotContain("Yeni metin");
		assertThat(jdbc.sql("select distinct actor_basis from audit.audit_entries where resource_id = ?::uuid").param(id)
			.query(String.class).list()).containsExactly("ADM");
	}

	@Test
	void onlyAdminWithManagementSessionManages() throws Exception {
		String body = "{\"title\":\"Başlık\",\"body\":\"Metin\"}";
		UUID coordinator = user("Koordinatör");
		role(coordinator, "KOR", "OPERATION", UUID.randomUUID());
		mvc.perform(json(post("/api/v1/public-announcements"), sessions.web(coordinator, true), body)).andExpect(status().isForbidden());
		UUID tek = user("Teknik");
		role(tek, "TEK", "ORGANIZATION", ORGANIZATION_ID);
		mvc.perform(json(post("/api/v1/public-announcements"), sessions.web(tek, true), body)).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/public-announcements"), sessions.web(admin, false), body)).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/public-announcements"), sessions.android(admin), body)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/public-announcements").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/public-announcements")).andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("select count(*) from notifications.public_announcements where title = 'Başlık'").query(Integer.class)
			.single()).isZero();
	}

	@Test
	void validationVersionConflictStateAndNotFound() throws Exception {
		mvc.perform(json(post("/api/v1/public-announcements"), adminWeb, "{\"title\":\"ab\",\"body\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("title", "body")));
		String created = mvc.perform(json(post("/api/v1/public-announcements"), adminWeb, "{\"title\":\"Başlık\",\"body\":\"Metin\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		mvc.perform(json(post("/api/v1/public-announcements/" + id + "/publish"), adminWeb, "{\"version\":7}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.currentVersion").value(1));
		mvc.perform(json(post("/api/v1/public-announcements/" + id + "/unpublish"), adminWeb, "{\"version\":1}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
		mvc.perform(json(post("/api/v1/public-announcements/" + id + "/publish"), adminWeb, "{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/public-announcements/" + UUID.randomUUID() + "/publish"), adminWeb, "{\"version\":1}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void publicListPagesNewestFirstAndRejectsLargeLimit() throws Exception {
		for (String title : List.of("Birinci", "İkinci", "Üçüncü")) {
			String created = mvc.perform(json(post("/api/v1/public-announcements"), adminWeb, "{\"title\":\"" + title + "\",\"body\":\"x\"}"))
				.andReturn().getResponse().getContentAsString();
			mvc.perform(json(post("/api/v1/public-announcements/" + JsonPath.read(created, "$.id") + "/publish"), adminWeb,
					"{\"version\":1}")).andExpect(status().isOk());
			Thread.sleep(5);
		}
		String first = mvc.perform(get("/api/v1/public/announcements?limit=2").with(ip("10.2.0.1")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].title").value("Üçüncü"))
			.andExpect(jsonPath("$.nextCursor").exists())
			.andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/public/announcements?limit=2&cursor=" + JsonPath.read(first, "$.nextCursor")).with(ip("10.2.0.1")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].title").value("Birinci"))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		mvc.perform(get("/api/v1/public/announcements?limit=51").with(ip("10.2.0.1"))).andExpect(status().isBadRequest());
	}

	@Test
	void publicEndpointIsRateLimitedPerClient() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(get("/api/v1/public/announcements").with(ip("10.9.9.9"))).andExpect(status().isOk());
		}
		mvc.perform(get("/api/v1/public/announcements").with(ip("10.9.9.9")))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().exists("Retry-After"));
		mvc.perform(get("/api/v1/public/announcements").with(ip("10.9.9.10"))).andExpect(status().isOk());
	}

	List<String> publicTitles(RequestPostProcessor client) throws Exception {
		String json = mvc.perform(get("/api/v1/public/announcements").with(client)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.items[*].title");
	}

	static RequestPostProcessor ip(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
		return request.with(authenticate(token)).contentType(MediaType.APPLICATION_JSON).content(body);
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

}
