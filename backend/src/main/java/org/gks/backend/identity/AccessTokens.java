package org.gks.backend.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.gks.backend.ApiProblems.Code;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

/**
 * Erişim belirteci: HS256 JWT, 15 dk (TD-04). Claim'ler §2.2: {@code sub}, {@code sid}, {@code cli}, {@code mfa},
 * {@code iat}, {@code exp}, {@code jti}. Roller ve izinler taşınmaz.
 */
@Component
class AccessTokens {

	static final String SESSION = "sid";
	static final String CLIENT = "cli";
	static final String MFA = "mfa";

	private final JwtEncoder encoder;
	private final JwtDecoder decoder;
	private final JwtProperties properties;
	private final Clock clock;

	AccessTokens(JwtEncoder encoder, JwtDecoder decoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.decoder = decoder;
		this.properties = properties;
		this.clock = clock;
	}

	Issued issue(UUID userId, UUID sessionId, ClientType client, boolean mfaVerified, Instant now) {
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.claim(SESSION, sessionId.toString())
			.claim(CLIENT, client.name())
			.claim(MFA, mfaVerified)
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(properties.keyId()).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new Issued(token, expiresAt);
	}

	/**
	 * İmza, anahtar sürümü, claim yapısı ve süre (§2.2 adım 1).
	 *
	 * @throws Rejected {@code AUTH_TOKEN_EXPIRED} veya {@code AUTH_UNAUTHENTICATED}
	 */
	AuthenticatedSession verify(String token) {
		Jwt jwt;
		try {
			jwt = decoder.decode(token);
		}
		catch (JwtException ex) {
			throw new Rejected(Code.AUTH_UNAUTHENTICATED);
		}
		Instant expiresAt = jwt.getExpiresAt();
		if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
			throw new Rejected(Code.AUTH_TOKEN_EXPIRED);
		}
		try {
			return new AuthenticatedSession(UUID.fromString(jwt.getSubject()),
					UUID.fromString(jwt.getClaimAsString(SESSION)),
					ClientType.valueOf(jwt.getClaimAsString(CLIENT)),
					Boolean.TRUE.equals(jwt.getClaimAsBoolean(MFA)));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new Rejected(Code.AUTH_UNAUTHENTICATED);
		}
	}

	/** Çözücüde çalışan yapı denetimi: beklenen anahtar sürümü ve zorunlu claim'ler. */
	static OAuth2TokenValidator<Jwt> structureValidator(String keyId) {
		return jwt -> {
			boolean valid = keyId.equals(jwt.getHeaders().get("kid"))
					&& jwt.getSubject() != null && jwt.getId() != null && jwt.getIssuedAt() != null
					&& jwt.hasClaim(SESSION) && jwt.hasClaim(CLIENT) && jwt.hasClaim(MFA);
			return valid ? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
		};
	}

	/** Belirteç değeri metne dönüşmez. */
	record Issued(String token, Instant expiresAt) {
		@Override
		public String toString() {
			return "AccessToken[expiresAt=" + expiresAt + "]";
		}
	}

	static final class Rejected extends RuntimeException {

		final Code code;

		Rejected(Code code) {
			super(code.name(), null, false, false);
			this.code = code;
		}

	}

}
