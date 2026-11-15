package org.gks.backend.access;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.PageCursor;
import org.gks.backend.ApiException.FieldError;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

/** AC-01 – AC-03 (backend-api-design.md §6). Yalnızca web ({@code x-client: [WEB]}). */
@RestController
@RequestMapping("/api/v1/role-assignments")
class RoleAssignmentController {

	private final RoleAssignments service;

	RoleAssignmentController(RoleAssignments service) {
		this.service = service;
	}

	record RoleAssignmentCreateRequest(UUID userId, Role role, ScopeType scopeType, UUID scopeId) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record RoleAssignmentPage(List<RoleAssignmentView> items, String nextCursor) {
	}

	/** AC-01. */
	@PostMapping
	ResponseEntity<RoleAssignmentView> grant(@RequestBody RoleAssignmentCreateRequest request,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		if (request == null || request.userId() == null) {
			errors.add(new FieldError("userId", "REQUIRED", "Kullanıcı zorunludur"));
		}
		if (request == null || request.role() == null) {
			errors.add(new FieldError("role", "REQUIRED", "Rol zorunludur"));
		}
		if (request == null || request.scopeType() == null) {
			errors.add(new FieldError("scopeType", "REQUIRED", "Kapsam türü zorunludur"));
		}
		if (request == null || request.scopeId() == null) {
			errors.add(new FieldError("scopeId", "REQUIRED", "Kapsam zorunludur"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		RoleAssignmentView view = service.grant(subject, request.userId(), request.role(), request.scopeType(),
				request.scopeId());
		return ResponseEntity.created(URI.create("/api/v1/role-assignments/" + view.id())).body(view);
	}

	/** AC-02. */
	@DeleteMapping("/{id}")
	ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication) {
		service.revoke(Subject.from(authentication).requireManagementSession(), id);
		return ResponseEntity.noContent().build();
	}

	/** AC-03. */
	@GetMapping
	RoleAssignmentPage list(@RequestParam(required = false) String userId, @RequestParam(required = false) String scopeId,
			@RequestParam(required = false) String role, @RequestParam(required = false) String active,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		UUID user = uuid("userId", userId, errors);
		UUID scope = uuid("scopeId", scopeId, errors);
		Role roleFilter = null;
		if (role != null) {
			try {
				roleFilter = Role.valueOf(role);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("role", "NOT_ALLOWED_VALUE", "Geçersiz rol"));
			}
		}
		Boolean activeFilter = null;
		if (active != null) {
			if (active.equals("true") || active.equals("false")) {
				activeFilter = Boolean.valueOf(active);
			}
			else {
				errors.add(new FieldError("active", "INVALID_FORMAT", "true veya false olmalıdır"));
			}
		}
		PageCursor cursorPage = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.LENIENT);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		RoleAssignments.Page page = service.list(subject, user, scope, roleFilter, activeFilter, cursorPage.after(),
				cursorPage.limit());
		return new RoleAssignmentPage(page.items(), page.lastId() == null ? null : PageCursor.encode(page.lastId()));
	}

	private static UUID uuid(String field, String value, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError(field, "INVALID_FORMAT", "Geçersiz kimlik"));
			return null;
		}
	}

}
