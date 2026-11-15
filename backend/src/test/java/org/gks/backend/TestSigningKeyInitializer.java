package org.gks.backend;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Testlerde her JVM için rastgele JWT imza anahtarı ve idempotency özet anahtarı; gerçek anahtar testlere girmez.
 * Kimlik filtresi web sunucusu açılırken oluştuğu için anahtar, bağlam kurulmadan önce ortama eklenir ({@code META-INF/spring.factories}).
 * Ortamda zaten bir anahtar varsa ona dokunulmaz.
 */
public class TestSigningKeyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final String PROPERTY = "gks.security.jwt.signing-key";
	private static final String KEY = randomKey();
	private static final String IDEMPOTENCY_PROPERTY = "gks.idempotency.hash-key";
	private static final String IDEMPOTENCY_KEY = randomKey();
	private static final String TOTP_PROPERTY = "gks.security.totp.encryption-key";
	private static final String TOTP_KEY = randomKey();
	private static final String INVITATION_PROPERTY = "gks.security.invitation.hash-key";
	private static final String INVITATION_KEY = randomKey();

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		String current = context.getEnvironment().getProperty(PROPERTY);
		if (current == null || current.isBlank()) {
			context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("testSigningKey", Map.of(PROPERTY, KEY)));
		}
		String totp = context.getEnvironment().getProperty(TOTP_PROPERTY);
		if (totp == null || totp.isBlank()) {
			context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("testTotpKey", Map.of(TOTP_PROPERTY, TOTP_KEY)));
		}
		String invitation = context.getEnvironment().getProperty(INVITATION_PROPERTY);
		if (invitation == null || invitation.isBlank()) {
			context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("testInvitationKey", Map.of(INVITATION_PROPERTY, INVITATION_KEY)));
		}
		String idempotency = context.getEnvironment().getProperty(IDEMPOTENCY_PROPERTY);
		if (idempotency == null || idempotency.isBlank()) {
			context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("testIdempotencyKey", Map.of(IDEMPOTENCY_PROPERTY, IDEMPOTENCY_KEY)));
		}
	}

	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
