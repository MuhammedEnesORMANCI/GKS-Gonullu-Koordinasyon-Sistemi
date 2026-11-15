package org.gks.backend.identity;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.gks.backend.ApiProblems;
import org.gks.backend.ApiProblems.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Erişim belirtecini doğrular ve kimliği güvenlik bağlamına koyar (backend-api-design.md §2.2, adım 1–4):
 * <ol>
 * <li>İmza, anahtar sürümü ve süre → {@code AUTH_TOKEN_EXPIRED} / {@code AUTH_UNAUTHENTICATED}.</li>
 * <li>Taşıma: {@code cli=ANDROID} yalnızca {@code Authorization: Bearer}, {@code cli=WEB} yalnızca
 * {@code __Host-access} çerezi → aksi {@code AUTH_UNAUTHENTICATED}.</li>
 * <li>Oturum iptali → {@code AUTH_SESSION_REVOKED}. Her istekte veritabanından denetlenir (LD-47); uygulama dışından
 * yapılan veya başka bir örnekteki iptal de sonraki istekte etkilidir.</li>
 * <li>Hesap durumu → {@code ACCOUNT_DISABLED}.</li>
 * <li>Zorunlu parola değişikliği → izinli uçlar dışında {@code 403 AUTH_PASSWORD_CHANGE_REQUIRED}.</li>
 * </ol>
 * Veritabanına ulaşılamazsa kimlik doğrulanmaz ve {@code 503 SERVICE_UNAVAILABLE} döner (LD-48): bu, geçersiz belirteç
 * veya iptal edilmiş oturumla aynı durum değildir; istemci oturumu sonlandırmamalıdır. Önbelleğe veya eski bir listeye
 * geri dönülmez.
 * Reddedilen belirteç yanıtı burada yazılmaz; neden {@link ApiProblems#AUTH_FAILURE} özniteliğine konur ve istek
 * kimliksiz devam eder. Böylece herkese açık uçlar (ör. {@code /ping}) bayat çerezden etkilenmez, korunan uçlar
 * nedenle birlikte reddedilir. Belirteç değeri günlüğe yazılmaz.
 */
public final class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private static final Logger log = LoggerFactory.getLogger(AccessTokenAuthenticationFilter.class);

	private final AccessTokens tokens;
	private final Users users;
	private final Clock clock;

	AccessTokenAuthenticationFilter(AccessTokens tokens, Users users, Clock clock) {
		this.tokens = tokens;
		this.users = users;
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		String token = null;
		ClientType transport = null;
		if (authorization != null) {
			if (authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
				token = authorization.substring(BEARER.length()).strip();
				transport = ClientType.ANDROID;
			}
			else {
				request.setAttribute(ApiProblems.AUTH_FAILURE, Code.AUTH_UNAUTHENTICATED);
			}
		}
		else {
			token = accessCookie(request);
			transport = token != null ? ClientType.WEB : null;
		}
		if (token != null && !token.isEmpty()) {
			Code failure = authenticate(token, transport, request);
			if (failure != null) {
				request.setAttribute(ApiProblems.AUTH_FAILURE, failure);
			}
		}
		chain.doFilter(request, response);
	}

	private Code authenticate(String token, ClientType transport, HttpServletRequest request) {
		AuthenticatedSession session;
		try {
			session = tokens.verify(token);
		}
		catch (AccessTokens.Rejected ex) {
			return ex.code;
		}
		if (session.client() != transport) {
			return Code.AUTH_UNAUTHENTICATED;
		}
		Users.AuthenticationState state;
		try {
			state = users.authenticationState(session.userId(), session.sessionId(), clock.instant()).orElse(null);
		}
		catch (DataAccessException ex) {
			// Belirteç ve kimlik günlüğe yazılmaz; eşleştirme traceId ile yapılır.
			log.error("Oturum denetimi yapılamadı; kimlik doğrulanmadı: {}", ex.getClass().getSimpleName());
			return Code.SERVICE_UNAVAILABLE;
		}
		if (state == null) {
			return Code.AUTH_UNAUTHENTICATED;          // kullanıcı yok
		}
		if (!state.sessionLive()) {
			return Code.AUTH_SESSION_REVOKED;          // §2.2 adım 3
		}
		if (state.status() == Users.Status.CLOSED) {
			return Code.AUTH_UNAUTHENTICATED;
		}
		if (state.status() == Users.Status.DISABLED) {
			return Code.ACCOUNT_DISABLED;              // §2.2 adım 4
		}
		if (state.mustChangePassword() && !allowedWhilePasswordChangeRequired(request)) {
			return Code.AUTH_PASSWORD_CHANGE_REQUIRED; // ID-09: yalnızca parola değişikliği ve oturum bilgisi
		}
		SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(session));
		return null;
	}

	/**
	 * {@code must_change_password} iken kimlikli olarak yalnızca şu uçlara izin verilir: profil ve bağlam okuma, parola
	 * değiştirme ve çıkış. Yenileme ve CSRF zaten herkese açıktır.
	 */
	static boolean allowedWhilePasswordChangeRequired(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return switch (path) {
			case "/api/v1/me", "/api/v1/me/context" -> "GET".equals(method);
			case "/api/v1/me/password", "/api/v1/auth/logout" -> "POST".equals(method);
			default -> false;
		};
	}

	private static String accessCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (AuthController.ACCESS_COOKIE.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
