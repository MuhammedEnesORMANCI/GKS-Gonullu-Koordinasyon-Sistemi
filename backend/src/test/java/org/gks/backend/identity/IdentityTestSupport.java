package org.gks.backend.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.gks.backend.Ids;
import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.access.OperationAccess;
import org.gks.backend.access.WebAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** identity testlerinin ortak bağlamı (tek PostgreSQL kapsayıcısı, aynı taklitler). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class IdentityTestSupport {

	static final String PASSWORD = "Deprem-Tatbikati-2026";
	static final String INSTALLATION = "android-install-0001";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	/** Web rolü kararı access modülündedir; testler sonucu belirler. */
	@MockitoBean
	WebAccess webAccess;

	@MockitoBean
	OperationAccess operationAccess;

	String newEmail() {
		return "kisi-" + UUID.randomUUID() + "@example.org";
	}

	UUID register(String email) {
		return accounts.register(new NewUserAccount(email, PASSWORD, "Ayşe Yılmaz", "Ayşe", null));
	}

	ResultActions login(String email, String password, String clientType) throws Exception {
		String installation = clientType.equals("ANDROID") ? ", \"installationId\": \"" + INSTALLATION + "\"" : "";
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"%s\"%s}"
				.formatted(email, password, clientType, installation)));
	}

	/** Android girişi; dönen gövdeden alan okumak için. */
	String androidLogin(String email) throws Exception {
		return login(email, PASSWORD, "ANDROID").andReturn().getResponse().getContentAsString();
	}

	static String field(String json, String path) {
		return JsonPath.read(json, path).toString();
	}

	/** Kimlik gerektiren ve organization modülüne giden bir uç: kimlik geçerliyse 404, değilse 401/403. */
	ResultActions protectedCall(String bearerToken) throws Exception {
		return mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())
			.header("Authorization", "Bearer " + bearerToken));
	}

	TestSessions sessions() {
		return new TestSessions(jdbc, jwtEncoder);
	}

	/** Rol ataması doğrudan tabloya yazılır (access modülü kararları gerçek tabloyu okur). */
	void grant(UUID userId, String role, UUID scopeId) {
		String scopeType = role.equals("KOR") || role.equals("GON") ? "OPERATION" : "ORGANIZATION";
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(Ids.newId(), userId, role, scopeType, scopeId)
			.update();
	}

	UUID organizationId() {
		return jdbc.sql("select id from organization.organizations").query(UUID.class).single();
	}

	/** {@code PLANNED} operasyon. */
	UUID insertOperation(String name, UUID createdBy) {
		UUID id = Ids.newId();
		jdbc.sql("insert into organization.operations (id, organization_id, name, created_by) values (?, ?, ?, ?)")
			.params(id, organizationId(), name, createdBy)
			.update();
		return id;
	}

	int activeSessionCount(UUID userId) {
		return jdbc.sql("select count(distinct family_id) from identity.refresh_tokens where user_id = ? and revoked_at is null")
			.param(userId)
			.query(Integer.class)
			.single();
	}

}
