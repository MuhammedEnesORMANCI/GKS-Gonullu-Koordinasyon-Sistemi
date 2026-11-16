package org.gks.backend;

import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Temel HTTP güvenliği (ADR-012, backend-api-design.md §2).
 * <p>
 * Kimlik, identity modülünün erişim belirteci filtresiyle kurulur (Android: {@code Authorization: Bearer},
 * web: {@code __Host-access} çerezi). Belirteçsiz veya reddedilmiş belirteçli istek, herkese açık uçlar dışında
 * {@code 401}/{@code 403} problem yanıtı alır.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	/** CSRF çerezi ve başlığı (§2.1 {@code csrfToken}). */
	static final String CSRF_COOKIE = "XSRF-TOKEN";
	static final String CSRF_HEADER = "X-CSRF-Token";

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, List<SecurityContributor> contributors) throws Exception {
		// Erişim belirteci (JWT) doğrulaması identity modülünün katkısıdır (ADR-012, §2.2 adım 1–4).
		for (SecurityContributor contributor : contributors) {
			contributor.contribute(http);
		}
		http
			// Durumsuz: sunucu oturumu ve JSESSIONID yok; kimlik her istekte belirteçle gelir (ADR-012).
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.csrf(csrf -> csrf
				.csrfTokenRepository(csrfTokenRepository())
				// Çift gönderim: başlıktaki ham değer çerezdeki değerle karşılaştırılır.
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				.requireCsrfProtectionMatcher(SecurityConfiguration::requiresCsrf))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/api/v1/ping").permitAll()          // ID-34
				.requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()     // ADR-023 kamu duyuruları (oran sınırlı)
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()     // ID-01
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()   // ID-02
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/enroll", "/api/v1/auth/mfa").permitAll() // ID-03/04 (bilet)
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll() // ID-05 (yenileme belirteci)
				.requestMatchers(HttpMethod.POST, "/api/v1/invitations/lookup", "/api/v1/invitations/accept")
				.permitAll()                                                           // ID-15/16 (davet kodu, LD-37)
				.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
				// RT-01: SSE akışının tamamlanma dağıtımı; ilk (REQUEST) dağıtım kimlik ve yetki denetiminden geçmiştir.
				.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors
				// Belirteç reddedildiyse nedeni (süresi dolmuş, oturum iptal, hesap devre dışı) yanıta yansır.
				.authenticationEntryPoint((request, response, ex) ->
						ApiProblems.write(request, response,
								request.getAttribute(ApiProblems.AUTH_FAILURE) instanceof ApiProblems.Code code
										? code : ApiProblems.Code.AUTH_UNAUTHENTICATED))
				.accessDeniedHandler((request, response, ex) ->
						ApiProblems.write(request, response, ex instanceof CsrfException
								? ApiProblems.Code.CSRF_TOKEN_INVALID
								: ApiProblems.Code.FORBIDDEN)));
		return http.build();
	}

	/** {@code XSRF-TOKEN}: HttpOnly değil (web okur), {@code Secure}, {@code SameSite=Strict}, {@code Path=/}. */
	private static CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName(CSRF_COOKIE);
		repository.setHeaderName(CSRF_HEADER);
		repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
		return repository;
	}

	/**
	 * CSRF, güvenli olmayan yöntemli ve çerez taşıyan isteklerde zorunludur (§2.1: "her çerezli istekte").
	 * Android {@code Authorization: Bearer} ile gelir ve çerez kullanmaz; bu isteklerde kimlik çerezden
	 * okunmayacağı için (§2.2 taşıma kuralı) CSRF gerekmez.
	 */
	static boolean requiresCsrf(HttpServletRequest request) {
		if (SAFE_METHODS.contains(request.getMethod())) {
			return false;
		}
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return false;
		}
		return request.getHeader(HttpHeaders.COOKIE) != null;
	}

}
