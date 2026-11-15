package org.gks.backend.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.identity.UserAdminService.UserDetail;
import org.gks.backend.identity.UserAdminService.UserPage;
import org.gks.backend.identity.UserAdminService.UserSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID-19 – ID-25 kullanıcı ve hesap yönetimi ({@code x-client: [WEB, ANDROID(MFA)]}, LD-61; TEK izinleri yalnızca web).
 * İzin ve izdüşüm kararları access'te ({@code UserAdminAccess}).
 */
@RestController
@RequestMapping("/api/v1/users")
class UserAdminController {

	private final UserAdminService service;

	UserAdminController(UserAdminService service) {
		this.service = service;
	}

	record ReasonRequest(String reason) {
	}

	/** ID-19. */
	@GetMapping
	UserPage list(@RequestParam(required = false) String status, @RequestParam(required = false) String operationId,
			@RequestParam(required = false) String includeTotal, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		Users.Status statusFilter = null;
		if (status != null) {
			try {
				statusFilter = Users.Status.valueOf(status);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("status", "NOT_ALLOWED_VALUE", "Geçersiz hesap durumu"));
			}
		}
		UUID operation = null;
		if (operationId != null) {
			try {
				operation = UUID.fromString(operationId);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("operationId", "INVALID_FORMAT", "Geçersiz kimlik"));
			}
		}
		boolean total = false;
		if (includeTotal != null) {
			if (includeTotal.equals("true") || includeTotal.equals("false")) {
				total = Boolean.parseBoolean(includeTotal);
			}
			else {
				errors.add(new FieldError("includeTotal", "INVALID_FORMAT", "true veya false olmalıdır"));
			}
		}
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return service.list(subject, statusFilter, operation, total, page);
	}

	/** ID-20. */
	@GetMapping("/{id}")
	UserDetail detail(@PathVariable UUID id, Authentication authentication) {
		return service.detail(Subject.from(authentication).requireManagementSession(), id);
	}

	/** ID-21 (gövde isteğe bağlı). */
	@PostMapping("/{id}/disable")
	UserSummary disable(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest request,
			Authentication authentication) {
		return service.disable(Subject.from(authentication).requireManagementSession(), id,
				request == null ? null : request.reason());
	}

	/** ID-22. */
	@PostMapping("/{id}/enable")
	UserSummary enable(@PathVariable UUID id, Authentication authentication) {
		return service.enable(Subject.from(authentication).requireManagementSession(), id);
	}

	/** ID-23. */
	@PostMapping("/{id}/revoke-sessions")
	ResponseEntity<Void> revokeSessions(@PathVariable UUID id, Authentication authentication) {
		service.revokeSessions(Subject.from(authentication).requireManagementSession(), id);
		return ResponseEntity.noContent().build();
	}

	/** ID-24. */
	@PostMapping("/{id}/reset-password")
	ResponseEntity<Void> resetPassword(@PathVariable UUID id, Authentication authentication) {
		service.resetPassword(Subject.from(authentication).requireManagementSession(), id);
		return ResponseEntity.noContent().build();
	}

	/** ID-25. */
	@PostMapping("/{id}/reset-mfa")
	ResponseEntity<Void> resetMfa(@PathVariable UUID id, Authentication authentication) {
		service.resetMfa(Subject.from(authentication).requireManagementSession(), id);
		return ResponseEntity.noContent().build();
	}

}
