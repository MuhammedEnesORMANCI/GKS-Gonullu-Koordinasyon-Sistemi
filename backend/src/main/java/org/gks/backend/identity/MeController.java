package org.gks.backend.identity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * ID-08 – ID-11 (backend-api-design.md §5). Her iki istemci türüne açıktır (W/A); yetki yalnızca kendi hesabı
 * üzerindedir. {@code must_change_password} iken {@code PATCH /me} dışındakiler izinlidir (filtre kapısı).
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final AccountService accounts;
	private final MeContextService context;
	private final SessionResponses responses;

	MeController(AccountService accounts, MeContextService context, SessionResponses responses) {
		this.responses = responses;
		this.accounts = accounts;
		this.context = context;
	}

	/** ID-09. */
	@GetMapping
	ResponseEntity<AccountService.MeProfile> profile(Authentication authentication) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(accounts.profile(AuthController.session(authentication).userId()));
	}

	/** ID-10: yalnızca {@code fullName}, {@code displayName}, {@code phone}. */
	@PatchMapping
	ResponseEntity<AccountService.MeProfile> update(Authentication authentication, @RequestBody JsonNode body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(accounts.updateProfile(AuthController.session(authentication).userId(), body));
	}

	/**
	 * ID-08 (LD-58): tüm oturumlar iptal; çağıran aynı istemci/MFA durumuyla yeni oturum ve yeni belirteç çifti alır
	 * ({@code 200 TokenResponse}; web'de çerezle).
	 */
	@PostMapping("/password")
	ResponseEntity<SessionResponses.TokenResponse> changePassword(Authentication authentication,
			@RequestBody(required = false) AccountService.PasswordChangeRequest request) {
		AuthenticatedSession session = AuthController.session(authentication);
		return responses.tokens(accounts.changePassword(session.userId(), session.sessionId(), request));
	}

	/** ID-11. */
	@GetMapping("/context")
	ResponseEntity<MeContextService.MeContext> context(Authentication authentication) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(context.of(AuthController.session(authentication)));
	}

}
