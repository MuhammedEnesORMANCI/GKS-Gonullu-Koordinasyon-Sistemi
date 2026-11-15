package org.gks.backend.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Idempotency;
import org.gks.backend.access.Subject;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * ID-12 – ID-18. Davet kodu yol parametresi değildir, gövdede taşınır (LD-37): {@code POST /invitations/lookup} ve
 * {@code POST /invitations/accept}. Kodun kendisi hiçbir yanıtta dönmez.
 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {

	private final InvitationService service;
	private final Idempotency idempotency;

	InvitationController(InvitationService service, Idempotency idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PageView<T>(List<T> items, String nextCursor) {
	}

	/** ID-12 ({@code x-client: [WEB]}, idempotency zorunlu). */
	@PostMapping
	ResponseEntity<String> create(@RequestBody(required = false) JsonNode body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		String idemKey = Idempotency.requiredKey(key);
		InvitationService.CreateInput input = InvitationService.parseCreate(body);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/invitations", Map.of(), body);
		return service.create(subject, input, request).toResponseEntity(id -> URI.create("/api/v1/invitations/" + id));
	}

	/** ID-13 ({@code x-client: [WEB]}). */
	@GetMapping
	PageView<InvitationService.InvitationView> list(@RequestParam(required = false) String scopeType,
			@RequestParam(required = false) String scopeId, @RequestParam(required = false) List<String> status,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		if (scopeType != null && !scopeType.equals("ORGANIZATION") && !scopeType.equals("OPERATION")) {
			errors.add(new FieldError("scopeType", "INVALID_ENUM", "ORGANIZATION veya OPERATION olmalıdır"));
		}
		UUID scope = uuid("scopeId", scopeId, errors);
		List<Invitations.Status> statuses = new ArrayList<>();
		if (status != null) {
			for (String value : status) {
				try {
					statuses.add(Invitations.Status.valueOf(value));
				}
				catch (IllegalArgumentException ex) {
					errors.add(new FieldError("status", "INVALID_ENUM", "Geçersiz davet durumu"));
				}
			}
		}
		int size = limit(limit, errors);
		UUID after = cursor(cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		InvitationService.Page page = service.list(subject, scopeType, scope, statuses, after, size);
		return new PageView<>(page.items(), page.lastId() == null ? null
				: Base64.getUrlEncoder().withoutPadding().encodeToString(page.lastId().toString()
					.getBytes(StandardCharsets.US_ASCII)));
	}

	/** ID-14 ({@code x-client: [WEB]}). */
	@PostMapping("/{id}/revoke")
	InvitationService.InvitationView revoke(@PathVariable UUID id, Authentication authentication) {
		return service.revoke(Subject.from(authentication).requireManagementSession(), id);
	}

	record CodeRequest(String code) {
		@Override
		public String toString() {
			return "CodeRequest[***]";
		}
	}

	/** ID-15 (kimliksiz; LD-37 gövdede kod). */
	@PostMapping("/lookup")
	ResponseEntity<InvitationService.InvitationPublicView> lookup(@RequestBody(required = false) CodeRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.lookup(request == null ? null : request.code()));
	}

	/**
	 * ID-16 (kimliksiz, Android; {@code x-idempotency: required-redacted}): yeni hesap ve Android oturumu
	 * ({@code 201 LoginResponse}). Aynı anahtarla tekrar {@code 409 ALREADY_COMPLETED} alır; belirteç yeniden üretilmez.
	 */
	@PostMapping("/accept")
	ResponseEntity<LoginResponse> accept(@RequestBody(required = false) InvitationService.AcceptInput input,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key) {
		String idemKey = Idempotency.requiredKey(key);
		if (input == null) {
			throw ApiException.validation(List.of(new FieldError("code", "REQUIRED", "Davet kodu zorunludur")));
		}
		var request = idempotency.request(idemKey, service.anonymousPrincipal(input.code()), "POST",
				"/invitations/accept", Map.of(), input);
		InvitationService.Accepted accepted = service.acceptNewUser(input, request);
		LoginResponse.SessionInfo session = new LoginResponse.SessionInfo(accepted.refresh().sessionId(),
				ClientType.ANDROID, false, accepted.refresh().expiresAt());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(new LoginResponse(false, null, null, false, accepted.access().token(), accepted.refresh().token(),
					accepted.access().expiresAt(), session));
	}

	/** ID-17 ({@code x-client: [ANDROID]}, idempotency zorunlu). */
	@PostMapping("/{id}/accept")
	ResponseEntity<String> acceptOperation(@PathVariable UUID id,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		return respond(id, key, authentication, true, "/invitations/{id}/accept");
	}

	/** ID-18 ({@code x-client: [ANDROID]}, idempotency zorunlu). */
	@PostMapping("/{id}/decline")
	ResponseEntity<String> decline(@PathVariable UUID id,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		return respond(id, key, authentication, false, "/invitations/{id}/decline");
	}

	private ResponseEntity<String> respond(UUID id, String key, Authentication authentication, boolean accept,
			String route) {
		Subject subject = Subject.from(authentication).requireAndroid();
		String idemKey = Idempotency.requiredKey(key);
		var request = idempotency.request(idemKey, subject.userId(), "POST", route, Map.of("id", id), null);
		return service.respond(subject, id, accept, request).toResponseEntity();
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

}
