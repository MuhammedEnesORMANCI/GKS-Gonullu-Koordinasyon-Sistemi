package org.gks.backend;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;

import jakarta.servlet.http.Cookie;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/**
 * Yalnızca testler: gerçek bir oturum satırı ({@code identity.refresh_tokens}) ve uygulamanın imza anahtarıyla
 * erişim belirteci üretir. Böylece istekler üretimdeki filtreden (imza, taşıma, oturum, hesap durumu) geçer.
 * Web+MFA oturumu, TOTP akışını her testte tekrarlamadan KOR/ADM işlemlerini sınamak içindir.
 */
public final class TestSessions {

	private final JdbcClient jdbc;
	private final JwtEncoder encoder;

	public TestSessions(JdbcClient jdbc, JwtEncoder encoder) {
		this.jdbc = jdbc;
		this.encoder = encoder;
	}

	/** {@code Authorization: Bearer} ile gönderilecek Android belirteci. */
	public String android(UUID userId) {
		return token(userId, "ANDROID", false);
	}

	/** {@code __Host-access} çereziyle gönderilecek web belirteci. */
	public String web(UUID userId, boolean mfaVerified) {
		return token(userId, "WEB", mfaVerified);
	}

	/**
	 * Belirteci kendi taşıma yoluyla ekler (§2.2 adım 2): Android → {@code Authorization: Bearer}; web →
	 * {@code __Host-access} çerezi ve çift gönderim CSRF belirteci (çerezli istekte zorunlu, §2.1).
	 */
	public static RequestPostProcessor authenticate(String token) {
		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
		if (payload.contains("\"cli\":\"WEB\"")) {
			return request -> {
				request.setCookies(new Cookie("__Host-access", token), new Cookie("XSRF-TOKEN", "test-csrf"));
				request.addHeader("X-CSRF-Token", "test-csrf");
				return request;
			};
		}
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}

	private String token(UUID userId, String client, boolean mfa) {
		UUID sessionId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.sql("""
				insert into identity.refresh_tokens (id, user_id, family_id, client, token_hash, mfa_verified, issued_at, expires_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(sessionId, userId, sessionId, client, "test-" + sessionId, mfa, Timestamp.from(now),
					Timestamp.from(now.plus(Duration.ofHours(12))))
			.update();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.claim("sid", sessionId.toString())
			.claim("cli", client)
			.claim("mfa", mfa)
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.id(UUID.randomUUID().toString())
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).keyId("v1").build(), claims))
			.getTokenValue();
	}

}
