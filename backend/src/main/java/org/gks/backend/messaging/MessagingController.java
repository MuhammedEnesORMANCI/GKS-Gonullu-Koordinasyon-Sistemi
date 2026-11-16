package org.gks.backend.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.Idempotency;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Subject;
import org.gks.backend.messaging.MessagingService.ChannelView;
import org.gks.backend.messaging.MessagingService.MessagePage;
import org.gks.backend.messaging.MessagingService.MessageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mesajlaşma uçları (ADR-024); web ve Android. Okuma hakkı olmayan kanal {@code 404} döner. Gönderim
 * {@code Idempotency-Key} ister (çevrimdışı kuyruğun tekrarında aynı mesaj döner).
 */
@RestController
@RequestMapping("/api/v1/channels")
class MessagingController {

	private final MessagingService service;

	MessagingController(MessagingService service) {
		this.service = service;
	}

	record SendRequest(String body) {
	}

	record ReadRequest(UUID messageId) {
	}

	record MuteRequest(Boolean muted) {
	}

	record SettingsRequest(Boolean adminsOnly) {
	}

	@GetMapping
	List<ChannelView> channels(Authentication authentication) {
		return service.channels(Subject.from(authentication));
	}

	@GetMapping("/{channelId}/messages")
	MessagePage messages(@PathVariable UUID channelId, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		List<FieldError> errors = new ArrayList<>();
		PageCursor page = PageCursor.parse(limit == null ? "30" : limit, cursor, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return service.messages(Subject.from(authentication), channelId, page);
	}

	@PostMapping("/{channelId}/messages")
	ResponseEntity<MessageView> send(@PathVariable UUID channelId, @RequestBody(required = false) SendRequest request,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		String clientKey = Idempotency.requiredKey(key);
		String body = request == null || request.body() == null ? "" : request.body().strip();
		if (body.isEmpty()) {
			throw ApiException.validation(List.of(new FieldError("body", "REQUIRED", "Mesaj boş olamaz")));
		}
		if (body.length() > MessagingService.BODY_MAX) {
			throw ApiException.validation(List.of(new FieldError("body", "TOO_LONG", "Mesaj en fazla 2000 karakter olabilir")));
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(service.send(Subject.from(authentication), channelId, body, clientKey));
	}

	@DeleteMapping("/{channelId}/messages/{messageId}")
	MessageView delete(@PathVariable UUID channelId, @PathVariable UUID messageId, Authentication authentication) {
		return service.delete(Subject.from(authentication), channelId, messageId);
	}

	@PostMapping("/{channelId}/messages/{messageId}/hide")
	MessageView hide(@PathVariable UUID channelId, @PathVariable UUID messageId, Authentication authentication) {
		return service.hide(Subject.from(authentication), channelId, messageId);
	}

	@PostMapping("/{channelId}/read")
	ResponseEntity<Void> read(@PathVariable UUID channelId, @RequestBody(required = false) ReadRequest request,
			Authentication authentication) {
		if (request == null || request.messageId() == null) {
			throw ApiException.validation(List.of(new FieldError("messageId", "REQUIRED", "Mesaj kimliği zorunludur")));
		}
		service.markRead(Subject.from(authentication), channelId, request.messageId());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{channelId}/mute")
	ResponseEntity<Void> mute(@PathVariable UUID channelId, @RequestBody(required = false) MuteRequest request,
			Authentication authentication) {
		if (request == null || request.muted() == null) {
			throw ApiException.validation(List.of(new FieldError("muted", "REQUIRED", "muted zorunludur")));
		}
		service.mute(Subject.from(authentication), channelId, request.muted());
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{channelId}")
	ResponseEntity<Void> settings(@PathVariable UUID channelId, @RequestBody(required = false) SettingsRequest request,
			Authentication authentication) {
		if (request == null || request.adminsOnly() == null) {
			throw ApiException.validation(List.of(new FieldError("adminsOnly", "REQUIRED", "adminsOnly zorunludur")));
		}
		service.setAdminsOnly(Subject.from(authentication), channelId, request.adminsOnly());
		return ResponseEntity.noContent().build();
	}

}
