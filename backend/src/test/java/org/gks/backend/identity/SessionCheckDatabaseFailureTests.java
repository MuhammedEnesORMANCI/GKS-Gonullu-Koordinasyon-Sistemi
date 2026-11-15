package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.identity.LoginApiTests.expectProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * LD-48: oturum denetimi veritabanına ulaşamazsa {@code 503 SERVICE_UNAVAILABLE} döner; geçersiz belirteç ve iptal
 * edilmiş oturum (401) ile karıştırılmaz. Gerçek bağlantı hatası: bu sınıfın kendi PostgreSQL kapsayıcısı test
 * sırasında durdurulur, bağlam sonra atılır.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "spring.datasource.hikari.connection-timeout=1000",
		"spring.datasource.hikari.validation-timeout=250" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionCheckDatabaseFailureTests extends IdentityTestSupport {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	AccessTokens accessTokens;

	@Test
	void unreachableDatabaseIsServiceUnavailableNotAnEndedSession(CapturedOutput output) throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String token = field(androidLogin(email), "$.accessToken");
		String[] parts = token.split("\\.");
		String tampered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
		String expired = accessTokens.issue(userId, UUID.randomUUID(), ClientType.ANDROID, false,
				Instant.now().minus(Duration.ofMinutes(16))).token();
		protectedCall(token).andExpect(status().isNotFound());

		postgres.stop();

		// Geçerli belirteç, ama oturum denetlenemiyor: 503, oturum sonu değil.
		expectProblem(protectedCall(token), 503, "SERVICE_UNAVAILABLE");
		// Veritabanına gitmeden reddedilen belirteçler 401 kalır.
		expectProblem(protectedCall(tampered), 401, "AUTH_UNAUTHENTICATED");
		expectProblem(protectedCall(expired), 401, "AUTH_TOKEN_EXPIRED");
		expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())), 401, "AUTH_UNAUTHENTICATED");
		// Herkese açık uç veritabanına bağlı değildir.
		mvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

		assertThat(output.getAll()).contains("Oturum denetimi yapılamadı").doesNotContain(token);
	}

}
