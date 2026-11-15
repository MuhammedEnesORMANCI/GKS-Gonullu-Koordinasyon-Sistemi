package org.gks.backend.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Idempotency;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.notifications.AnnouncementService.AnnouncementPage;
import org.gks.backend.notifications.AnnouncementService.AnnouncementView;
import org.gks.backend.notifications.AnnouncementService.Preview;
import org.gks.backend.notifications.AnnouncementService.Target;
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

/** NT-01, NT-02, NT-03, NT-06 ({@code x-client: [WEB]}; kurumsal izin, MFA kuralı access'te). */
@RestController
@RequestMapping("/api/v1/announcements")
class AnnouncementController {

	static final int TITLE_MIN = 3;
	static final int TITLE_MAX = 120;
	static final int BODY_MAX = 2000;

	private final AnnouncementService service;
	private final Idempotency idempotency;

	AnnouncementController(AnnouncementService service, Idempotency idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	record TargetRequest(UUID operationId, List<UUID> userIds, List<UUID> groupIds, Boolean organization) {
		Target target() {
			return new Target(operationId, userIds, groupIds, Boolean.TRUE.equals(organization));
		}
	}

	record CreateRequest(UUID operationId, List<UUID> userIds, List<UUID> groupIds, Boolean organization, String title,
			String body, Boolean confirm) {
	}

	/** NT-01. */
	@PostMapping("/preview")
	Preview preview(@RequestBody(required = false) TargetRequest request, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		return service.preview(subject, (request == null ? new TargetRequest(null, null, null, null) : request).target());
	}

	/** NT-02 ({@code Z}: {@code Idempotency-Key} zorunlu). */
	@PostMapping
	ResponseEntity<String> create(@RequestBody(required = false) CreateRequest request,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		String idemKey = Idempotency.requiredKey(key);
		CreateRequest body = request == null ? new CreateRequest(null, null, null, null, null, null, null) : request;
		List<FieldError> errors = new ArrayList<>();
		String title = body.title() == null ? null : body.title().strip();
		String text = body.body() == null ? null : body.body().strip();
		if (title == null || title.isEmpty()) {
			errors.add(new FieldError("title", "REQUIRED", "Başlık zorunludur"));
		}
		else if (title.length() < TITLE_MIN) {
			errors.add(new FieldError("title", "TOO_SHORT", "Başlık en az 3 karakter olmalıdır"));
		}
		else if (title.length() > TITLE_MAX) {
			errors.add(new FieldError("title", "TOO_LONG", "Başlık en fazla 120 karakter olabilir"));
		}
		if (text == null || text.isEmpty()) {
			errors.add(new FieldError("body", "REQUIRED", "Duyuru metni zorunludur"));
		}
		else if (text.length() > BODY_MAX) {
			errors.add(new FieldError("body", "TOO_LONG", "Duyuru metni en fazla 2000 karakter olabilir"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		var idem = idempotency.request(idemKey, subject.userId(), "POST", "/announcements", Map.of(), body);
		Target target = new Target(body.operationId(), body.userIds(), body.groupIds(), Boolean.TRUE.equals(body.organization()));
		return service.create(subject, target, title, text, Boolean.TRUE.equals(body.confirm()), idem).toResponseEntity();
	}

	/** NT-03. */
	@GetMapping
	AnnouncementPage list(@RequestParam(required = false) UUID operationId, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return service.list(subject, operationId, page);
	}

	/** NT-06. */
	@GetMapping("/{id}")
	AnnouncementView get(@PathVariable UUID id, Authentication authentication) {
		return service.get(Subject.from(authentication).requireManagementSession(), id);
	}

}
