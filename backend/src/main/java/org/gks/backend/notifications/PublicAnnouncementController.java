package org.gks.backend.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.notifications.PublicAnnouncementService.AdminView;
import org.gks.backend.notifications.PublicAnnouncementService.Page;
import org.gks.backend.notifications.PublicAnnouncementService.PublicView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kamu duyuruları (ADR-023).
 * <ul>
 * <li>{@code GET /api/v1/public/announcements}: kimlik doğrulamasız, yalnızca yayındakiler, sayfalı (en fazla 50),
 * IP başına dakikada sınırlı ({@code 429 RATE_LIMITED}).</li>
 * <li>{@code /api/v1/public-announcements}: ADM yönetimi (liste, ayrıntı, oluştur, düzenle, yayımla, yayından kaldır);
 * web veya MFA'lı Android yönetim oturumu; değişiklikler {@code version} ile.</li>
 * </ul>
 */
@RestController
class PublicAnnouncementController {

	static final int TITLE_MIN = 3;
	static final int TITLE_MAX = 160;
	static final int BODY_MAX = 5000;
	static final int PUBLIC_MAX_LIMIT = 50;

	private final PublicAnnouncementService service;
	private final PublicRateLimiter limiter;

	PublicAnnouncementController(PublicAnnouncementService service, PublicRateLimiter limiter) {
		this.service = service;
		this.limiter = limiter;
	}

	record ContentRequest(String title, String body, Integer version) {
	}

	record VersionRequest(Integer version) {
	}

	@GetMapping("/api/v1/public/announcements")
	ResponseEntity<Page<PublicView>> published(@RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, HttpServletRequest request) {
		long retryAfter = limiter.acquire(request.getRemoteAddr());
		if (retryAfter > 0) {
			throw ApiException.rateLimited(retryAfter);
		}
		List<FieldError> errors = new ArrayList<>();
		PageCursor page = PageCursor.parse(limit == null ? "10" : limit, cursor, errors);
		if (page.limit() > PUBLIC_MAX_LIMIT) {
			errors.add(new FieldError("limit", "OUT_OF_RANGE", "limit 1 ile 50 arasında olmalıdır"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.published(page));
	}

	@GetMapping("/api/v1/public-announcements")
	Page<AdminView> list(@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		List<FieldError> errors = new ArrayList<>();
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return service.list(subject, page);
	}

	@GetMapping("/api/v1/public-announcements/{id}")
	AdminView get(@PathVariable UUID id, Authentication authentication) {
		return service.get(Subject.from(authentication).requireManagementSession(), id);
	}

	@PostMapping("/api/v1/public-announcements")
	ResponseEntity<AdminView> create(@RequestBody(required = false) ContentRequest request, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		ContentRequest body = validated(request, false);
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(subject, body.title(), body.body()));
	}

	@PatchMapping("/api/v1/public-announcements/{id}")
	AdminView update(@PathVariable UUID id, @RequestBody(required = false) ContentRequest request, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		ContentRequest body = validated(request, true);
		return service.update(subject, id, body.version(), body.title(), body.body());
	}

	@PostMapping("/api/v1/public-announcements/{id}/publish")
	AdminView publish(@PathVariable UUID id, @RequestBody(required = false) VersionRequest request, Authentication authentication) {
		return service.publish(Subject.from(authentication).requireManagementSession(), id, version(request));
	}

	@PostMapping("/api/v1/public-announcements/{id}/unpublish")
	AdminView unpublish(@PathVariable UUID id, @RequestBody(required = false) VersionRequest request, Authentication authentication) {
		return service.unpublish(Subject.from(authentication).requireManagementSession(), id, version(request));
	}

	private static int version(VersionRequest request) {
		if (request == null || request.version() == null || request.version() < 1) {
			throw ApiException.validation(List.of(new FieldError("version", "REQUIRED", "Sürüm zorunludur")));
		}
		return request.version();
	}

	private static ContentRequest validated(ContentRequest request, boolean versionRequired) {
		ContentRequest r = request == null ? new ContentRequest(null, null, null) : request;
		List<FieldError> errors = new ArrayList<>();
		String title = r.title() == null ? null : r.title().strip();
		String text = r.body() == null ? null : r.body().strip();
		if (title == null || title.isEmpty()) {
			errors.add(new FieldError("title", "REQUIRED", "Başlık zorunludur"));
		}
		else if (title.length() < TITLE_MIN) {
			errors.add(new FieldError("title", "TOO_SHORT", "Başlık en az 3 karakter olmalıdır"));
		}
		else if (title.length() > TITLE_MAX) {
			errors.add(new FieldError("title", "TOO_LONG", "Başlık en fazla 160 karakter olabilir"));
		}
		if (text == null || text.isEmpty()) {
			errors.add(new FieldError("body", "REQUIRED", "Metin zorunludur"));
		}
		else if (text.length() > BODY_MAX) {
			errors.add(new FieldError("body", "TOO_LONG", "Metin en fazla 5000 karakter olabilir"));
		}
		if (versionRequired && (r.version() == null || r.version() < 1)) {
			errors.add(new FieldError("version", "REQUIRED", "Sürüm zorunludur"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new ContentRequest(title, text, r.version());
	}

}
