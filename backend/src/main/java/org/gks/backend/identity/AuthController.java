package org.gks.backend.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID-01 – ID-07 (backend-api-design.md §5). Belirteç içeren yanıtlar önbelleğe alınmaz. {@code /auth/*} uçları
 * {@code Idempotency-Key} başlığını yok sayar; yanıt hiçbir yerde saklanmaz (LD-42).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	static final String ACCESS_COOKIE = SessionResponses.ACCESS_COOKIE;
	static final String REFRESH_COOKIE = SessionResponses.REFRESH_COOKIE;

	private final LoginService loginService;
	private final MfaService mfaService;
	private final SessionService sessionService;
	private final SessionResponses responses;

	AuthController(LoginService loginService, MfaService mfaService, SessionService sessionService,
			SessionResponses responses) {
		this.loginService = loginService;
		this.mfaService = mfaService;
		this.sessionService = sessionService;
		this.responses = responses;
	}

	/** ID-01: giriş öncesi CSRF belirteci; {@code XSRF-TOKEN} çerezi bu çağrıda yazılır. */
	@GetMapping("/csrf")
	ResponseEntity<CsrfView> csrf(CsrfToken token) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new CsrfView(token.getHeaderName(), token.getToken()));
	}

	record CsrfView(String headerName, String token) {
	}

	/** ID-02. ADM/KOR web girişinde oturum açılmaz; yalnızca MFA bileti döner (§2.3). */
	@PostMapping("/login")
	ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
		LoginService.Result result = loginService.login(request);
		if (result.mfaRequired()) {
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(true,
					result.mfaEnrollmentRequired(), result.mfaTicket(), result.mustChangePassword(), null, null, null, null));
		}
		return sessionResponse(result);
	}

	record MfaEnrollRequest(String mfaTicket) {
		@Override
		public String toString() {
			return "MfaEnrollRequest[***]";
		}
	}

	record MfaVerifyRequest(String mfaTicket, String code) {
		@Override
		public String toString() {
			return "MfaVerifyRequest[***]";
		}
	}

	/** ID-03: gizli anahtar yalnızca bu yanıtta bir kez döner. */
	@PostMapping("/mfa/enroll")
	ResponseEntity<MfaService.Enrollment> enroll(@RequestBody MfaEnrollRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(mfaService.enroll(request == null ? null : request.mfaTicket()));
	}

	/** ID-04: doğru kod → {@code mfaVerified=true} web oturumu ve çerezler. */
	@PostMapping("/mfa")
	ResponseEntity<LoginResponse> verify(@RequestBody MfaVerifyRequest request) {
		if (request == null) {
			throw ApiException.of(Code.AUTH_MFA_REQUIRED);
		}
		return sessionResponse(mfaService.verify(request.mfaTicket(), request.code()));
	}

	record RefreshRequest(String refreshToken) {
		@Override
		public String toString() {
			return "RefreshRequest[***]";
		}
	}

	/** ID-05: Android gövdeden ({@code RefreshRequest}), web {@code __Secure-refresh} çerezinden (CSRF zorunlu). */
	@PostMapping("/refresh")
	ResponseEntity<SessionResponses.TokenResponse> refresh(@RequestBody(required = false) RefreshRequest body, HttpServletRequest request) {
		SessionService.Rotation rotation = body != null && body.refreshToken() != null
				? sessionService.refresh(body.refreshToken(), ClientType.ANDROID)
				: sessionService.refresh(cookie(request, REFRESH_COOKIE), ClientType.WEB);
		return responses.tokens(rotation);
	}

	/** ID-06. */
	@PostMapping("/logout")
	ResponseEntity<Void> logout(Authentication authentication) {
		AuthenticatedSession session = session(authentication);
		sessionService.logout(session.userId(), session.sessionId(), session.webSession() ? ClientType.WEB : ClientType.ANDROID);
		return SessionResponses.cleared();
	}

	/** ID-07. */
	@PostMapping("/logout-all")
	ResponseEntity<Void> logoutAll(Authentication authentication) {
		AuthenticatedSession session = session(authentication);
		sessionService.logoutAll(session.userId(), session.sessionId(), session.webSession() ? ClientType.WEB : ClientType.ANDROID);
		return SessionResponses.cleared();
	}

	static AuthenticatedSession session(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedSession session) {
			return session;
		}
		throw ApiException.of(Code.AUTH_UNAUTHENTICATED);
	}

	private ResponseEntity<LoginResponse> sessionResponse(LoginService.Result result) {
		LoginResponse.SessionInfo session = new LoginResponse.SessionInfo(result.refresh().sessionId(), result.client(),
				result.mfaVerified(), result.refresh().expiresAt());
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		if (result.client() == ClientType.WEB) {
			responses.setWebCookies(response, result.access(), result.refresh());
			return response.body(new LoginResponse(false, null, null, result.mustChangePassword(), null, null,
					result.access().expiresAt(), session));
		}
		return response.body(new LoginResponse(false, null, null, result.mustChangePassword(), result.access().token(),
				result.refresh().token(), result.access().expiresAt(), session));
	}

	private static String cookie(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (name.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
