package org.gks.backend.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Oturum belirteçlerinin istemciye verilişi (§2.1): web'de yalnızca {@code HttpOnly} çerezler, Android'de gövde.
 * Belirteç içeren yanıtlar önbelleğe alınmaz.
 */
@Component
class SessionResponses {

	static final String ACCESS_COOKIE = "__Host-access";
	static final String REFRESH_COOKIE = "__Secure-refresh";
	static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	/** §5 {@code TokenResponse}; web'de belirteçler yalnızca çerezde. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TokenResponse(String accessToken, String refreshToken, UUID sessionId, Instant accessTokenExpiresAt) {
		@Override
		public String toString() {
			return "TokenResponse[session=" + sessionId + "]";
		}
	}

	ResponseEntity<TokenResponse> tokens(SessionService.Rotation rotation) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		if (rotation.client() == ClientType.WEB) {
			setWebCookies(response, rotation.access(), rotation.refresh());
			return response.body(new TokenResponse(null, null, rotation.refresh().sessionId(),
					rotation.access().expiresAt()));
		}
		return response.body(new TokenResponse(rotation.access().token(), rotation.refresh().token(),
				rotation.refresh().sessionId(), rotation.access().expiresAt()));
	}

	void setWebCookies(ResponseEntity.BodyBuilder response, AccessTokens.Issued access, RefreshTokens.Issued refresh) {
		// Kalan süre yukarı yuvarlanır: çerez, sunucudaki mutlak süreden (LD-19) önce bitmez ve sonra da en fazla 1 sn yaşar.
		long millis = Duration.between(Instant.now(), refresh.expiresAt()).toMillis();
		Duration refreshAge = Duration.ofSeconds(Math.max(0, (millis + 999) / 1000));
		// Erişim çerezi oturum boyunca yaşar; belirtecin 15 dk ömrünü JWT `exp` belirler ve sunucu doğrular. Çerez
		// belirteçle birlikte bitseydi tarayıcı onu silerdi; istek kimliksiz gider, AUTH_TOKEN_EXPIRED yerine
		// AUTH_UNAUTHENTICATED döner ve istemci yenileme yapmadan oturumu sonlandırırdı (§2.2, mapping §1.3).
		response.header(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, access.token(), "/", refreshAge).toString());
		response.header(HttpHeaders.SET_COOKIE,
				cookie(REFRESH_COOKIE, refresh.token(), REFRESH_COOKIE_PATH, refreshAge).toString());
	}

	/** Çıkışta web çerezleri silinir (Android'de zararsız). */
	static ResponseEntity<Void> cleared() {
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO).toString())
			.header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", REFRESH_COOKIE_PATH, Duration.ZERO).toString())
			.build();
	}

	/** §2.1: {@code HttpOnly; Secure; SameSite=Strict}. {@code __Host-} öneki alan adı vermeyi ve {@code Path≠/}'yi yasaklar. */
	private static ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
		return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Strict").path(path).maxAge(maxAge)
			.build();
	}

}
