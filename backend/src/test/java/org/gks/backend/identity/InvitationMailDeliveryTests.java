package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * Davet e-postasının gerçek yolu: taklit yok. Gerçek yetki (ADM rolü + OR-01 ile kendini KOR atama, web paneliyle
 * aynı), gerçek {@link InvitationMailer} ve compose.yaml'daki Mailpit sürümü. ID-12 sonrası e-postanın Mailpit'e
 * ulaştığı ve kodun yalnızca e-postada bulunduğu doğrulanır.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvitationMailDeliveryTests {

	private static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@SuppressWarnings("resource")
	static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.33.1")
		.withExposedPorts(1025, 8025);

	static {
		MAILPIT.start();
	}

	@DynamicPropertySource
	static void mail(DynamicPropertyRegistry registry) {
		registry.add("spring.mail.host", MAILPIT::getHost);
		registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void invitationFromWebPanelReachesMailpit() throws Exception {
		UUID admin = accounts.register(new NewUserAccount("yonetici-" + UUID.randomUUID() + "@example.org",
				"Deprem-Tatbikati-2026", "Kurum Yöneticisi", "Yönetici", null));
		jdbc.sql("""
				insert into access.role_assignments (id, user_id, role, scope_type, scope_id)
				values (?, ?, 'ADM', 'ORGANIZATION', ?)""").params(UUID.randomUUID(), admin, ORGANIZATION_ID).update();
		String token = new TestSessions(jdbc, jwtEncoder).web(admin, true);

		String operation = mvc.perform(post("/api/v1/operations").with(authenticate(token))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Tatbikat\", \"coordinatorIds\": [\"%s\"]}".formatted(admin)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String operationId = JsonPath.read(operation, "$.id");

		String email = "gonullu-" + UUID.randomUUID() + "@example.org";
		mvc.perform(post("/api/v1/invitations").with(authenticate(token))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(email, operationId)))
			.andExpect(status().isCreated());

		assertThat(jdbc.sql("select count(*) from identity.invitations where email = ?").param(email)
			.query(Long.class).single()).isEqualTo(1);

		String message = awaitMessageTo(email);
		assertThat(message).contains("Gönüllü Koordinasyon Sistemi daveti").contains("Tatbikat");
		assertThat(message).containsPattern("#[23456789A-HJKMNP-TV-Z]{8}");
	}

	/** Gönderim commit sonrası (afterCommit) ve eşzamanlıdır; yine de Mailpit'in dizinlemesi için kısa bekleme. */
	private static String awaitMessageTo(String email) throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String base = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			String search = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/search?query=to:" + email)).build(),
					HttpResponse.BodyHandlers.ofString()).body();
			java.util.List<String> ids = JsonPath.read(search, "$.messages[*].ID");
			if (!ids.isEmpty()) {
				return http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + ids.getFirst())).build(),
						HttpResponse.BodyHandlers.ofString()).body();
			}
			Thread.sleep(200);
		}
		throw new AssertionError("Davet e-postası Mailpit'e ulaşmadı: " + email);
	}

}
