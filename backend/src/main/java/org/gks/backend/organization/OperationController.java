package org.gks.backend.organization;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Idempotency;
import org.gks.backend.access.Subject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** Operasyon uçları OR-01 – OR-06 (backend-api-design.md §7.1). */
@RestController
@RequestMapping("/api/v1/operations")
class OperationController {

	private final OperationService service;
	private final Idempotency idempotency;

	OperationController(OperationService service, Idempotency idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PageView<T>(List<T> items, String nextCursor) {
	}

	/** OR-01 ({@code x-client: [WEB]}; idempotency destekli "d"). */
	@PostMapping
	ResponseEntity<String> create(@RequestBody JsonNode body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		String idemKey = Idempotency.optionalKey(key);
		OperationCreate input = OperationCreate.parse(body, Instant.now());
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/operations", Map.of(), body);
		return service.create(subject, input, request).toResponseEntity(id -> URI.create("/api/v1/operations/" + id));
	}

	/** OR-04 ({@code x-client: [WEB, ANDROID]}). */
	@GetMapping
	PageView<OperationView> list(@RequestParam(required = false) List<String> state,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = Subject.from(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<OperationState> states = EnumSet.noneOf(OperationState.class);
		if (state != null) {
			for (String value : state) {
				try {
					states.add(OperationState.valueOf(value));
				}
				catch (IllegalArgumentException ex) {
					errors.add(new FieldError("state", "NOT_ALLOWED_VALUE", "Geçersiz operasyon durumu"));
				}
			}
		}
		int size = limit(limit, errors);
		UUID after = cursor(cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		OperationService.Page<OperationView> page = service.list(subject, states, after, size);
		return new PageView<>(page.items(), cursorOf(page.lastId()));
	}

	/** OR-05 ({@code x-client: [WEB, ANDROID]}). */
	@GetMapping("/{id}")
	OperationView get(@PathVariable UUID id, Authentication authentication) {
		return service.get(id, Subject.from(authentication));
	}

	/** OR-06 ({@code x-client: [WEB]}). */
	@GetMapping("/{id}/participants")
	PageView<OperationService.ParticipantView> participants(@PathVariable UUID id,
			@RequestParam(required = false) String notInGroup, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		UUID group = null;
		if (notInGroup != null) {
			try {
				group = UUID.fromString(notInGroup);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("notInGroup", "INVALID_FORMAT", "Geçersiz grup kimliği"));
			}
		}
		int size = limit(limit, errors);
		UUID after = cursor(cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		OperationService.Page<OperationService.ParticipantView> page = service.participants(subject, id, group, after,
				size);
		return new PageView<>(page.items(), cursorOf(page.lastId()));
	}

	/** OR-02 ({@code x-client: [WEB]}). */
	@PatchMapping("/{id}")
	OperationView update(@PathVariable UUID id, @RequestBody JsonNode body, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		OperationUpdate update = OperationUpdate.parse(body, Instant.now());
		return service.update(id, update, subject);
	}

	/** OR-03 ({@code x-client: [WEB]}). */
	@PostMapping("/{id}/state")
	OperationView changeState(@PathVariable UUID id, @RequestBody OperationStateRequest request,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		if (request.targetState() == null) {
			throw ApiException.validation(List.of(new FieldError("targetState", "REQUIRED", "Hedef durum zorunludur")));
		}
		return service.changeState(id, request.targetState(), subject);
	}

	record OperationStateRequest(OperationState targetState) {
	}

	/** §3.5: {@code limit} 1..100 (varsayılan 50). */
	private static int limit(String value, List<FieldError> errors) {
		if (value == null) {
			return 50;
		}
		try {
			int size = Integer.parseInt(value);
			if (size < 1 || size > 100) {
				errors.add(new FieldError("limit", "OUT_OF_RANGE", "limit 1 ile 100 arasında olmalıdır"));
			}
			return size;
		}
		catch (NumberFormatException ex) {
			errors.add(new FieldError("limit", "INVALID_FORMAT", "limit sayı olmalıdır"));
			return 50;
		}
	}

	/** §3.5: opak base64url imleç. */
	private static UUID cursor(String value, List<FieldError> errors) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII));
		}
		catch (IllegalArgumentException ex) {
			errors.add(new FieldError("cursor", "INVALID_FORMAT", "Geçersiz imleç"));
			return null;
		}
	}

	private static String cursorOf(UUID lastId) {
		return lastId == null ? null
				: Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
	}

}
