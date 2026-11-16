package org.gks.backend.alerts;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Idempotency;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.alerts.AlertViews.AlertPreview;
import org.gks.backend.alerts.AlertViews.AlertRecipientView;
import org.gks.backend.alerts.AlertViews.AlertSummary;
import org.gks.backend.alerts.AlertViews.AlertView;
import org.gks.backend.alerts.AlertViews.GeneralCandidate;
import org.gks.backend.alerts.AlertViews.MyAlertView;
import org.gks.backend.alerts.AlertViews.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alarm uçları AL-01, AL-03 – AL-16 (§11.1). AL-02 (taslak düzenleme) bu aşamada yok: istemci taslağı siler ve
 * yeniden oluşturur. Yönetim uçları kurumsal oturum ister (web veya MFA'lı Android, ADR-014); alıcı uçları
 * Android'dendir ({@code x-client: [ANDROID]}).
 */
@RestController
class AlertController {

	private final AlertService service;
	private final Idempotency idempotency;

	AlertController(AlertService service, Idempotency idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	record TargetsRequest(List<UUID> userIds, List<UUID> groupIds) {
	}

	record LocationRequest(Double lat, Double lon) {
	}

	record DraftRequest(String message, String requestedAction, String priority, TargetsRequest targets,
			UUID correctsAlertId, LocationRequest eventLocation) {
	}

	/** ADR-026: hedefler birleştirilebilir; {@code organization} yalnızca ADM. */
	record GeneralTargetsRequest(Boolean organization, List<UUID> operationIds, List<UUID> groupIds, List<UUID> userIds) {
	}

	record GeneralDraftRequest(String message, String requestedAction, GeneralTargetsRequest targets,
			LocationRequest eventLocation) {
	}

	record SendRequest(Integer version, String audienceHash, Boolean confirmed) {
	}

	record ReasonRequest(String reason) {
	}

	record ResendRequest(List<UUID> recipientIds) {
	}

	record SignalRequest(Instant at) {
	}

	record ResponseRequest(String option, String respondedAtDevice) {
	}

	/** AL-01. */
	@PostMapping("/api/v1/operations/{operationId}/alerts")
	ResponseEntity<AlertPreview> create(@PathVariable UUID operationId, @RequestBody DraftRequest body,
			Authentication authentication) {
		AlertPreview preview = service.create(management(authentication), operationId, draft(body));
		return ResponseEntity.created(URI.create("/api/v1/alerts/" + preview.alertId())).body(preview);
	}

	/** ADR-026 Genel Acil Alarm: taslak + tekilleştirilmiş alıcı önizlemesi. Gönderim AL-05 ile ({@code confirmed=true}). */
	@PostMapping("/api/v1/general-alerts")
	ResponseEntity<AlertPreview> createGeneral(@RequestBody GeneralDraftRequest body, Authentication authentication) {
		AlertPreview preview = service.createGeneral(management(authentication), generalDraft(body));
		return ResponseEntity.created(URI.create("/api/v1/alerts/" + preview.alertId())).body(preview);
	}

	/** RAPOR_7 madde 3: Genel Acil Alarm kişi seçicisi (sunucu tarafı arama + sayfalama). */
	@GetMapping("/api/v1/general-alerts/candidates")
	Page<GeneralCandidate> generalCandidates(@RequestParam(required = false) String q,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = management(authentication);
		List<FieldError> errors = new ArrayList<>();
		if (q != null && q.strip().length() > 100) {
			errors.add(new FieldError("q", "TOO_LONG", "Arama en fazla 100 karakter olabilir"));
		}
		PageCursor page = PageCursor.parse(limit, cursor, errors);
		requireValid(errors);
		return service.generalCandidates(subject, q, page);
	}

	/** AL-03. */
	@DeleteMapping("/api/v1/alerts/{alertId}")
	ResponseEntity<Void> delete(@PathVariable UUID alertId, Authentication authentication) {
		service.deleteDraft(management(authentication), alertId);
		return ResponseEntity.noContent().build();
	}

	/** AL-04. */
	@GetMapping("/api/v1/alerts/{alertId}/preview")
	AlertPreview preview(@PathVariable UUID alertId, Authentication authentication) {
		return service.preview(management(authentication), alertId);
	}

	/** AL-05 (Z). */
	@PostMapping("/api/v1/alerts/{alertId}/send")
	ResponseEntity<String> send(@PathVariable UUID alertId, @RequestBody SendRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.requiredKey(key);
		List<FieldError> errors = new ArrayList<>();
		if (body == null || body.version() == null) {
			errors.add(new FieldError("version", "REQUIRED", "Sürüm zorunludur"));
		}
		if (body == null || body.audienceHash() == null || body.audienceHash().isBlank()) {
			errors.add(new FieldError("audienceHash", "REQUIRED", "Önizleme özeti zorunludur"));
		}
		requireValid(errors);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/alerts/{id}/send", Map.of("id", alertId),
				body);
		return service.send(subject, alertId, body.version(), body.audienceHash(), Boolean.TRUE.equals(body.confirmed()),
				request).toResponseEntity();
	}

	/** AL-06. */
	@GetMapping("/api/v1/operations/{operationId}/alerts")
	Page<AlertSummary> listForOperation(@PathVariable UUID operationId, @RequestParam(required = false) List<String> state,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = management(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<AlertState> states = states(state, Set.of(), errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.listForOperation(subject, operationId, states, page);
	}

	/** AL-16 (varsayılan {@code OPEN}). */
	@GetMapping("/api/v1/alerts")
	Page<AlertSummary> list(@RequestParam(required = false) List<String> state, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = management(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<AlertState> states = states(state, Set.of(AlertState.OPEN), errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.list(subject, states, page);
	}

	/** AL-07: {@code AlertView} veya alıcıya {@code MyAlertView}. */
	@GetMapping("/api/v1/alerts/{alertId}")
	Object get(@PathVariable UUID alertId, Authentication authentication) {
		return service.get(Subject.from(authentication), alertId);
	}

	/** AL-09. */
	@GetMapping("/api/v1/alerts/{alertId}/recipients")
	Page<AlertRecipientView> recipients(@PathVariable UUID alertId, @RequestParam(required = false) List<String> state,
			@RequestParam(required = false) String responseOption, Authentication authentication) {
		Subject subject = management(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<RecipientState> states = EnumSet.noneOf(RecipientState.class);
		for (String value : split(state)) {
			try {
				states.add(RecipientState.valueOf(value));
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("state", "INVALID_VALUE", "Geçersiz durum: " + value));
			}
		}
		ResponseOption option = null;
		if (responseOption != null) {
			try {
				option = ResponseOption.valueOf(responseOption);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("responseOption", "INVALID_VALUE", "Geçersiz seçenek"));
			}
		}
		requireValid(errors);
		return service.recipients(subject, alertId, states, option);
	}

	/** AL-10 (Z). */
	@PostMapping("/api/v1/alerts/{alertId}/resend")
	ResponseEntity<String> resend(@PathVariable UUID alertId, @RequestBody(required = false) ResendRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.requiredKey(key);
		List<UUID> ids = body == null || body.recipientIds() == null ? List.of() : body.recipientIds();
		if (ids.size() > AlertService.MAX_RECIPIENTS) {
			throw ApiException.validation(List.of(new FieldError("recipientIds", "TOO_MANY", "En fazla 500 alıcı")));
		}
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/alerts/{id}/resend", Map.of("id", alertId),
				new ResendRequest(ids));
		return service.resend(subject, alertId, ids, request).toResponseEntity();
	}

	/** AL-11 (Z). */
	@PostMapping("/api/v1/alerts/{alertId}/cancel")
	ResponseEntity<String> cancel(@PathVariable UUID alertId, @RequestBody(required = false) ReasonRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.requiredKey(key);
		ReasonRequest reason = body == null ? new ReasonRequest(null) : body;
		if (reason.reason() != null && reason.reason().length() > 500) {
			throw ApiException.validation(List.of(new FieldError("reason", "TOO_LONG", "En fazla 500 karakter")));
		}
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/alerts/{id}/cancel", Map.of("id", alertId),
				reason);
		return service.cancel(subject, alertId, reason.reason() != null && !reason.reason().isBlank(), request)
			.toResponseEntity();
	}

	/** AL-12. */
	@PostMapping("/api/v1/alerts/{alertId}/close")
	AlertView close(@PathVariable UUID alertId, Authentication authentication) {
		return service.close(management(authentication), alertId);
	}

	/** AL-08. */
	@GetMapping("/api/v1/me/alerts")
	Page<MyAlertView> mine(@RequestParam(required = false) List<String> state, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireAndroid();
		List<FieldError> errors = new ArrayList<>();
		Set<AlertState> states = states(state, Set.of(AlertState.OPEN), errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.mine(subject, states, page);
	}

	/** AL-13. */
	@PostMapping("/api/v1/alert-recipients/{recipientId}/delivered")
	ResponseEntity<Void> delivered(@PathVariable UUID recipientId, @RequestBody(required = false) SignalRequest body,
			Authentication authentication) {
		service.signal(Subject.from(authentication).requireAndroid(), recipientId, RecipientState.DELIVERED);
		return ResponseEntity.noContent().build();
	}

	/** AL-14. */
	@PostMapping("/api/v1/alert-recipients/{recipientId}/seen")
	ResponseEntity<Void> seen(@PathVariable UUID recipientId, @RequestBody(required = false) SignalRequest body,
			Authentication authentication) {
		service.signal(Subject.from(authentication).requireAndroid(), recipientId, RecipientState.SEEN);
		return ResponseEntity.noContent().build();
	}

	/** AL-15 (Z). */
	@PostMapping("/api/v1/alert-recipients/{recipientId}/responses")
	ResponseEntity<String> respond(@PathVariable UUID recipientId, @RequestBody ResponseRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = Subject.from(authentication).requireAndroid();
		String idemKey = Idempotency.requiredKey(key);
		List<FieldError> errors = new ArrayList<>();
		ResponseOption option = null;
		if (body == null || body.option() == null) {
			errors.add(new FieldError("option", "REQUIRED", "Yanıt seçeneği zorunludur"));
		}
		else {
			try {
				option = ResponseOption.valueOf(body.option());
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("option", "INVALID_VALUE", "Geçersiz yanıt seçeneği"));
			}
		}
		Instant device = null;
		if (body == null || body.respondedAtDevice() == null) {
			errors.add(new FieldError("respondedAtDevice", "REQUIRED", "Cihaz zamanı zorunludur"));
		}
		else {
			try {
				device = Instant.parse(body.respondedAtDevice());
			}
			catch (DateTimeParseException ex) {
				errors.add(new FieldError("respondedAtDevice", "INVALID_FORMAT", "ISO-8601 zaman bekleniyor"));
			}
		}
		requireValid(errors);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/alert-recipients/{id}/responses",
				Map.of("id", recipientId), body);
		return service.respond(subject, recipientId, option, device, request).toResponseEntity();
	}

	// ---- doğrulama ----

	static AlertService.Draft draft(DraftRequest body) {
		List<FieldError> errors = new ArrayList<>();
		if (body == null) {
			throw ApiException.validation(List.of(new FieldError("message", "REQUIRED", "Mesaj zorunludur")));
		}
		String message = body.message() == null ? null : body.message().strip();
		if (message == null || message.isEmpty()) {
			errors.add(new FieldError("message", "REQUIRED", "Mesaj zorunludur"));
		}
		else if (message.length() > AlertService.MESSAGE_MAX) {
			errors.add(new FieldError("message", "TOO_LONG", "Mesaj en fazla 500 karakter olabilir"));
		}
		String action = body.requestedAction() == null || body.requestedAction().isBlank() ? null
				: body.requestedAction().strip();
		if (action != null && action.length() > AlertService.ACTION_MAX) {
			errors.add(new FieldError("requestedAction", "TOO_LONG", "İstenen eylem en fazla 200 karakter olabilir"));
		}
		String priority = body.priority() == null ? "CRITICAL" : body.priority();
		if (!priority.equals("CRITICAL") && !priority.equals("HIGH")) {
			errors.add(new FieldError("priority", "INVALID_VALUE", "Öncelik CRITICAL veya HIGH olmalıdır"));
		}
		List<UUID> userIds = body.targets() == null || body.targets().userIds() == null ? List.of()
				: body.targets().userIds().stream().distinct().toList();
		List<UUID> groupIds = body.targets() == null || body.targets().groupIds() == null ? List.of()
				: body.targets().groupIds().stream().distinct().toList();
		if (userIds.isEmpty() && groupIds.isEmpty()) {
			errors.add(new FieldError("targets", "REQUIRED", "En az bir kişi veya grup seçilmelidir"));
		}
		if (userIds.size() > AlertService.MAX_RECIPIENTS || groupIds.size() > AlertService.MAX_RECIPIENTS) {
			errors.add(new FieldError("targets", "TOO_MANY", "Her hedef listesi en fazla 500 öğe içerebilir"));
		}
		if (userIds.stream().anyMatch(java.util.Objects::isNull) || groupIds.stream().anyMatch(java.util.Objects::isNull)) {
			errors.add(new FieldError("targets", "INVALID_VALUE", "Boş kimlik"));
		}
		double[] location = location(body.eventLocation(), errors);
		requireValid(errors);
		return new AlertService.Draft(message, action, priority, userIds, groupIds, body.correctsAlertId(),
				location == null ? null : location[0], location == null ? null : location[1]);
	}

	static AlertService.GeneralDraft generalDraft(GeneralDraftRequest body) {
		List<FieldError> errors = new ArrayList<>();
		if (body == null) {
			throw ApiException.validation(List.of(new FieldError("message", "REQUIRED", "Mesaj zorunludur")));
		}
		String message = body.message() == null ? null : body.message().strip();
		if (message == null || message.isEmpty()) {
			errors.add(new FieldError("message", "REQUIRED", "Mesaj zorunludur"));
		}
		else if (message.length() > AlertService.MESSAGE_MAX) {
			errors.add(new FieldError("message", "TOO_LONG", "Mesaj en fazla 500 karakter olabilir"));
		}
		String action = body.requestedAction() == null || body.requestedAction().isBlank() ? null
				: body.requestedAction().strip();
		if (action != null && action.length() > AlertService.ACTION_MAX) {
			errors.add(new FieldError("requestedAction", "TOO_LONG", "İstenen eylem en fazla 200 karakter olabilir"));
		}
		GeneralTargetsRequest t = body.targets();
		boolean organization = t != null && Boolean.TRUE.equals(t.organization());
		List<UUID> ops = t == null || t.operationIds() == null ? List.of() : t.operationIds().stream().distinct().toList();
		List<UUID> groupIds = t == null || t.groupIds() == null ? List.of() : t.groupIds().stream().distinct().toList();
		List<UUID> userIds = t == null || t.userIds() == null ? List.of() : t.userIds().stream().distinct().toList();
		if (!organization && ops.isEmpty() && groupIds.isEmpty() && userIds.isEmpty()) {
			errors.add(new FieldError("targets", "REQUIRED", "En az bir hedef seçilmelidir"));
		}
		if (ops.size() > 100 || groupIds.size() > AlertService.MAX_RECIPIENTS || userIds.size() > AlertService.MAX_RECIPIENTS) {
			errors.add(new FieldError("targets", "TOO_MANY", "Hedef listesi çok uzun"));
		}
		if (java.util.stream.Stream.of(ops, groupIds, userIds).flatMap(List::stream).anyMatch(java.util.Objects::isNull)) {
			errors.add(new FieldError("targets", "INVALID_VALUE", "Boş kimlik"));
		}
		double[] location = location(body.eventLocation(), errors);
		requireValid(errors);
		return new AlertService.GeneralDraft(message, action, organization, ops, groupIds, userIds,
				location == null ? null : location[0], location == null ? null : location[1]);
	}

	/** İsteğe bağlı olay yeri: enlem/boylam birlikte ve aralıkta. */
	private static double[] location(LocationRequest location, List<FieldError> errors) {
		if (location == null) {
			return null;
		}
		if (location.lat() == null || location.lon() == null) {
			errors.add(new FieldError("eventLocation", "REQUIRED", "Enlem ve boylam birlikte verilmelidir"));
			return null;
		}
		if (location.lat().isNaN() || location.lat() < -90 || location.lat() > 90) {
			errors.add(new FieldError("eventLocation.lat", "OUT_OF_RANGE", "Enlem -90 ile 90 arasında olmalıdır"));
			return null;
		}
		if (location.lon().isNaN() || location.lon() < -180 || location.lon() > 180) {
			errors.add(new FieldError("eventLocation.lon", "OUT_OF_RANGE", "Boylam -180 ile 180 arasında olmalıdır"));
			return null;
		}
		return new double[] { location.lat(), location.lon() };
	}

	private static Set<AlertState> states(List<String> values, Set<AlertState> defaults, List<FieldError> errors) {
		List<String> list = split(values);
		if (list.isEmpty()) {
			return defaults;
		}
		Set<AlertState> states = EnumSet.noneOf(AlertState.class);
		for (String value : list) {
			try {
				states.add(AlertState.valueOf(value));
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("state", "INVALID_VALUE", "Geçersiz durum: " + value));
			}
		}
		return states;
	}

	private static List<String> split(List<String> values) {
		List<String> result = new ArrayList<>();
		if (values != null) {
			for (String value : values) {
				for (String part : value.split(",")) {
					if (!part.isBlank()) {
						result.add(part.strip());
					}
				}
			}
		}
		return result;
	}

	private static Subject management(Authentication authentication) {
		return Subject.from(authentication).requireManagementSession();
	}

	private static void requireValid(List<FieldError> errors) {
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
	}

}
