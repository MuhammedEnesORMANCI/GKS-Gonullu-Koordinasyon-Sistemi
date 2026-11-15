package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import org.gks.backend.access.Decision;
import static org.gks.backend.identity.LoginApiTests.expectProblem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/** Erişim belirteci doğrulaması (backend-api-design.md §2.2 adım 1–4; ADR-012, ADR-013). */
class AccessTokenAuthenticationTests extends IdentityTestSupport {

	@Autowired
	AccessTokens accessTokens;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	JwtProperties jwtProperties;

	@Test
	void claimsFollowContractAndCarryNoRoles() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String body = androidLogin(email);
		String[] parts = field(body, "$.accessToken").split("\\.");

		String header = decode(parts[0]);
		String claims = decode(parts[1]);
		assertThat(header).contains("\"alg\":\"HS256\"").contains("\"kid\":\"v1\"");
		assertThat(claims)
			.contains("\"sub\":\"" + userId + "\"")
			.contains("\"sid\":\"" + field(body, "$.session.sessionId") + "\"")
			.contains("\"cli\":\"ANDROID\"")
			.contains("\"mfa\":false")
			.contains("\"iat\":").contains("\"exp\":").contains("\"jti\":")
			.doesNotContain("role").doesNotContain("scope").doesNotContain("authorit").doesNotContain(email);
	}

	@Test
	void authenticatedNameIsUserIdForOtherModules() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String token = field(androidLogin(email), "$.accessToken");
		UUID operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, created_by)
				values (?, '01900000-0000-7000-8000-000000000001', 'Sel tatbikatı', ?)""")
			.params(operationId, userId).update();
		given(operationAccess.canView(any(), eq(operationId))).willReturn(Decision.ALLOW);
		given(operationAccess.canManage(any(), any())).willReturn(Decision.FORBIDDEN);   // katılımcı izdüşümü

		mvc.perform(get("/api/v1/operations/{id}", operationId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Sel tatbikatı"));
	}

	@Test
	void missingTokenIsUnauthenticated() throws Exception {
		expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())), 401, "AUTH_UNAUTHENTICATED");
	}

	@Test
	void expiredTokenIsReportedAsExpired() throws Exception {
		UUID userId = register(newEmail());
		String token = accessTokens.issue(userId, UUID.randomUUID(), ClientType.ANDROID, false,
				Instant.now().minus(Duration.ofMinutes(16))).token();

		expectProblem(protectedCall(token), 401, "AUTH_TOKEN_EXPIRED");
	}

	/**
	 * Web: erişim çerezi oturum boyu yaşadığı için süresi dolmuş belirteç çerezde gelmeye devam eder ve istemcinin
	 * yenilemesi için {@code AUTH_TOKEN_EXPIRED} alır (oturum sonu sayılan {@code AUTH_UNAUTHENTICATED} değil).
	 */
	@Test
	void expiredWebCookieIsReportedAsExpired() throws Exception {
		UUID userId = register(newEmail());
		String token = accessTokens.issue(userId, UUID.randomUUID(), ClientType.WEB, true,
				Instant.now().minus(Duration.ofMinutes(16))).token();

		expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())
			.cookie(new Cookie(SessionResponses.ACCESS_COOKIE, token))), 401, "AUTH_TOKEN_EXPIRED");
	}

	@Test
	void tamperedForeignKeyedUnsignedOrUnknownKidTokensAreRejected() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String valid = field(androidLogin(email), "$.accessToken");
		String[] parts = valid.split("\\.");

		String tampered = parts[0] + "." + parts[1] + "." + flip(parts[2]);
		String forgedClaims = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
				decode(parts[1]).replace("\"mfa\":false", "\"mfa\":true").getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
		String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString(
				"{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
		String foreignKey = encode(encoderFor(randomKey(), "v1"), "v1", userId, true);
		String unknownKid = encode(encoderFor(jwtProperties.secretKey(), "v2"), "v2", userId, true);
		String withoutSessionClaim = encode(jwtEncoder, "v1", userId, false);

		for (String token : new String[] { tampered, forgedClaims, unsigned, foreignKey, unknownKid,
				withoutSessionClaim, "bozuk" }) {
			expectProblem(protectedCall(token), 401, "AUTH_UNAUTHENTICATED");
		}
	}

	/** §2.2 adım 2: Android belirteci çerezde, web belirteci başlıkta kabul edilmez. */
	@Test
	void tokenMustArriveOverItsOwnTransport() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String androidToken = field(androidLogin(email), "$.accessToken");
		String webToken = webSession(userId);

		expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())
			.cookie(new Cookie("__Host-access", androidToken))), 401, "AUTH_UNAUTHENTICATED");
		expectProblem(protectedCall(webToken), 401, "AUTH_UNAUTHENTICATED");
		mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(new Cookie("__Host-access", webToken)))
			.andExpect(status().isNotFound());
	}

	@Test
	void nonBearerAuthorizationIsRejected() throws Exception {
		expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID())
			.header("Authorization", "Basic YTpi")), 401, "AUTH_UNAUTHENTICATED");
	}

	/**
	 * LD-47: oturum geçerliliği her istekte veritabanından denetlenir. Oturum geçerli ⇔ aynı kullanıcının
	 * {@code family_id = sid} zincirinde {@code revoked_at IS NULL AND replaced_by IS NULL AND expires_at > now()}
	 * olan bir satır var (backend-api-design.md §2.2 adım 3; AC-03.3, AC-06.1).
	 */
	@Nested
	class SessionValidity {

		/** 1. Uygulama dışından (veya başka bir örnekten) yapılan iptal yeniden başlatmadan etkilidir. */
		@Test
		void revocationWrittenOutsideTheApplicationIsEffectiveOnNextRequest() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			String token = field(body, "$.accessToken");
			protectedCall(token).andExpect(status().isNotFound());

			revokeFamily(field(body, "$.session.sessionId"), "LOGOUT");

			expectProblem(protectedCall(token), 401, "AUTH_SESSION_REVOKED");
		}

		/** 2. AC-06.1: tüm oturumlar iptal edilince her cihazın ve web oturumunun belirteci sonraki istekte 401. */
		@Test
		void revokingAllSessionsRejectsEveryDeviceAndTheWebSession() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String phone = field(androidLogin(email), "$.accessToken");
			String tablet = field(androidLogin(email), "$.accessToken");
			String web = webSession(userId);
			protectedCall(phone).andExpect(status().isNotFound());
			protectedCall(tablet).andExpect(status().isNotFound());
			webCall(web).andExpect(status().isNotFound());

			jdbc.sql("""
					update identity.refresh_tokens set revoked_at = now(), revoke_reason = 'ADMIN_REVOKE'
					 where user_id = ? and revoked_at is null""").param(userId).update();

			expectProblem(protectedCall(phone), 401, "AUTH_SESSION_REVOKED");
			expectProblem(protectedCall(tablet), 401, "AUTH_SESSION_REVOKED");
			expectProblem(webCall(web), 401, "AUTH_SESSION_REVOKED");
		}

		/** 3. Döndürülmüş zincir: eski satır değiştirildi, halef canlı; oturum geçerli kalır. Tek ifadede (DB E-15). */
		@Test
		void rotatedChainStaysValid() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			UUID sessionId = UUID.fromString(field(body, "$.session.sessionId"));

			int rotated = jdbc.sql("""
					with successor as (
					    insert into identity.refresh_tokens (id, user_id, device_id, family_id, client, token_hash, expires_at)
					    select gen_random_uuid(), user_id, device_id, family_id, client, md5(random()::text),
					           now() + interval '30 days'
					      from identity.refresh_tokens where id = :id
					    returning id)
					update identity.refresh_tokens
					   set replaced_by = (select id from successor), last_used_at = now()
					 where id = :id and revoked_at is null and replaced_by is null""")
				.param("id", sessionId).update();

			assertThat(rotated).isEqualTo(1);
			protectedCall(field(body, "$.accessToken")).andExpect(status().isNotFound());
		}

		/** 4. Çalınma tespiti: zincirin tamamı iptal edilir; 401. */
		@Test
		void reuseDetectionRevokesTheWholeChain() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			UUID sessionId = UUID.fromString(field(body, "$.session.sessionId"));
			jdbc.sql("""
					insert into identity.refresh_tokens (id, user_id, family_id, client, token_hash, expires_at)
					select gen_random_uuid(), user_id, family_id, client, md5(random()::text), now() + interval '30 days'
					  from identity.refresh_tokens where id = ?""").param(sessionId).update();

			revokeFamily(sessionId.toString(), "REUSE_DETECTED");

			expectProblem(protectedCall(field(body, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
		}

		/** 5. Yenileme belirtecinin süresi kendiliğinden dolmuş oturum; 401 (LD-19 mutlak süre ile uyumlu). */
		@Test
		void naturallyExpiredSessionIsRejected() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			jdbc.sql("update identity.refresh_tokens set expires_at = now() - interval '1 second' where family_id = ?")
				.param(UUID.fromString(field(body, "$.session.sessionId"))).update();

			expectProblem(protectedCall(field(body, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
		}

		/** 6. Temizlik işi zinciri sildiyse güvenli yönde başarısız olunur. */
		@Test
		void deletedChainIsRejected() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			jdbc.sql("delete from identity.refresh_tokens where family_id = ?")
				.param(UUID.fromString(field(body, "$.session.sessionId"))).update();

			expectProblem(protectedCall(field(body, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
		}

		/** 7. Doğru anahtarla imzalı ama başka kullanıcının oturum kimliğini taşıyan belirteç kabul edilmez. */
		@Test
		void sessionOfAnotherUserIsRejected() throws Exception {
			String victim = newEmail();
			register(victim);
			UUID victimSession = UUID.fromString(field(androidLogin(victim), "$.session.sessionId"));
			UUID attacker = register(newEmail());

			String forged = accessTokens.issue(attacker, victimSession, ClientType.ANDROID, false, Instant.now()).token();

			expectProblem(protectedCall(forged), 401, "AUTH_SESSION_REVOKED");
		}

		/** 8. Öncelik (§2.2): iptal edilmiş oturum (adım 3) hesap durumundan (adım 4) önce gelir. */
		@Test
		void revokedSessionTakesPrecedenceOverDisabledAccount() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String body = androidLogin(email);
			jdbc.sql("update identity.users set status = 'DISABLED', disabled_at = now() where id = ?").param(userId)
				.update();
			expectProblem(protectedCall(field(body, "$.accessToken")), 403, "ACCOUNT_DISABLED");

			revokeFamily(field(body, "$.session.sessionId"), "ACCOUNT_DISABLED");

			expectProblem(protectedCall(field(body, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
		}

		/** 9. Herkese açık uçlar iptal edilmiş belirteçten etkilenmez. */
		@Test
		void publicEndpointIgnoresRevokedToken() throws Exception {
			String email = newEmail();
			register(email);
			String body = androidLogin(email);
			revokeFamily(field(body, "$.session.sessionId"), "LOGOUT");

			mvc.perform(get("/api/v1/ping").header("Authorization", "Bearer " + field(body, "$.accessToken")))
				.andExpect(status().isNoContent());
		}

		private ResultActions webCall(String token) throws Exception {
			return mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(new Cookie("__Host-access", token)));
		}

		private void revokeFamily(String sessionId, String reason) {
			jdbc.sql("update identity.refresh_tokens set revoked_at = now(), revoke_reason = ? where family_id = ?")
				.params(reason, UUID.fromString(sessionId)).update();
		}

	}

	/** Gerçek bir web oturum satırı (web girişi rol ister, rol kararı access modülünde) ve ona bağlı erişim belirteci. */
	String webSession(UUID userId) {
		UUID sessionId = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.refresh_tokens (id, user_id, family_id, client, token_hash, expires_at)
				values (?, ?, ?, 'WEB', md5(random()::text), now() + interval '12 hours')""")
			.params(sessionId, userId, sessionId).update();
		return accessTokens.issue(userId, sessionId, ClientType.WEB, false, Instant.now()).token();
	}

	@Test
	void disabledAccountTokenIsForbidden() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String token = field(androidLogin(email), "$.accessToken");
		jdbc.sql("update identity.users set status = 'DISABLED', disabled_at = now() where id = ?").param(userId)
			.update();

		expectProblem(protectedCall(token), 403, "ACCOUNT_DISABLED");
	}

	@Test
	void publicEndpointsIgnoreBadTokens() throws Exception {
		mvc.perform(get("/api/v1/ping").header("Authorization", "Bearer bozuk")).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/ping").cookie(new Cookie("__Host-access", "bozuk"))).andExpect(status().isNoContent());
	}

	@Test
	void roleDecisionsStayInAccessModule() throws Exception {
		String email = newEmail();
		register(email);
		given(operationAccess.canView(any(), any())).willReturn(Decision.FORBIDDEN);
		String token = field(androidLogin(email), "$.accessToken");
		UUID operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, created_by)
				values (?, '01900000-0000-7000-8000-000000000001', 'Sel tatbikatı',
				        (select id from identity.users where email = ?))""")
			.params(operationId, email).update();

		expectProblem(mvc.perform(get("/api/v1/operations/{id}", operationId).header("Authorization", "Bearer " + token)),
				403, "FORBIDDEN");
	}

	private String encode(JwtEncoder encoder, String kid, UUID userId, boolean withSession) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(userId.toString()).claim("cli", "ANDROID")
			.claim("mfa", false).issuedAt(now).expiresAt(now.plusSeconds(600)).id(UUID.randomUUID().toString());
		if (withSession) {
			claims.claim("sid", UUID.randomUUID().toString());
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).keyId(kid).build(),
				claims.build())).getTokenValue();
	}

	private static SecretKey randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new SecretKeySpec(key, "HmacSHA256");
	}

	private static JwtEncoder encoderFor(SecretKey key, String kid) {
		OctetSequenceKey jwk = new OctetSequenceKey.Builder(key).keyID(kid).algorithm(JWSAlgorithm.HS256).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
	}

	private static String decode(String part) {
		return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
	}

	private static String flip(String signature) {
		char first = signature.charAt(0);
		return (first == 'A' ? 'B' : 'A') + signature.substring(1);
	}

}
