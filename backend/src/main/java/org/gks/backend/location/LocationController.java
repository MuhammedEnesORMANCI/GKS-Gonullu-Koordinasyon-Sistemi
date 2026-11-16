package org.gks.backend.location;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.access.Subject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Konum uçları LC-01 – LC-05 (§10.1). Paylaşım uçları Android'dendir; LC-05 kurumsal oturumla (web veya MFA'lı
 * Android) yalnızca KOR'a açıktır.
 */
@RestController
class LocationController {

	private static final Set<String> AVAILABILITY_REASONS = Set.of("PERMISSION_DENIED", "GPS_OFF", "NO_FIX");
	private static final Set<String> FRESHNESS = Set.of("LIVE", "STALE", "LAST_KNOWN");

	private final LocationService service;

	LocationController(LocationService service) {
		this.service = service;
	}

	record StartRequest(String availability, String reason) {
	}

	record MeasurementRequest(Double lat, Double lon, Double accuracy, String measuredAt) {
	}

	/** LC-01. Gövde isteğe bağlı: {@code {"availability":"UNAVAILABLE","reason":"GPS_OFF"}} (N-07). */
	@PostMapping("/api/v1/operations/{operationId}/location-sharing")
	LocationService.SharingView start(@PathVariable UUID operationId, @RequestBody(required = false) StartRequest body,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireAndroid();
		String reason = null;
		if (body != null && body.availability() != null) {
			if ("UNAVAILABLE".equals(body.availability())) {
				if (body.reason() == null || !AVAILABILITY_REASONS.contains(body.reason())) {
					throw ApiException.validation(List.of(new FieldError("reason", "INVALID_VALUE",
							"PERMISSION_DENIED, GPS_OFF veya NO_FIX olmalıdır")));
				}
				reason = body.reason();
			}
			else if (!"AVAILABLE".equals(body.availability())) {
				throw ApiException.validation(List.of(new FieldError("availability", "INVALID_VALUE",
						"AVAILABLE veya UNAVAILABLE olmalıdır")));
			}
		}
		return service.start(subject, operationId, reason);
	}

	/** LC-02. */
	@DeleteMapping("/api/v1/operations/{operationId}/location-sharing")
	ResponseEntity<Void> stop(@PathVariable UUID operationId, Authentication authentication) {
		service.stop(Subject.from(authentication).requireAndroid(), operationId);
		return ResponseEntity.noContent().build();
	}

	/** LC-03. */
	@PostMapping("/api/v1/operations/{operationId}/locations")
	ResponseEntity<Void> measure(@PathVariable UUID operationId, @RequestBody MeasurementRequest body,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireAndroid();
		List<FieldError> errors = new ArrayList<>();
		if (body == null || body.lat() == null || body.lat() < -90 || body.lat() > 90) {
			errors.add(new FieldError("lat", "OUT_OF_RANGE", "-90..90"));
		}
		if (body == null || body.lon() == null || body.lon() < -180 || body.lon() > 180) {
			errors.add(new FieldError("lon", "OUT_OF_RANGE", "-180..180"));
		}
		if (body == null || body.accuracy() == null || body.accuracy() < 0 || body.accuracy() > 10_000) {
			errors.add(new FieldError("accuracy", "OUT_OF_RANGE", "0..10000 m"));
		}
		Instant measuredAt = null;
		if (body == null || body.measuredAt() == null) {
			errors.add(new FieldError("measuredAt", "REQUIRED", "Ölçüm zamanı zorunludur"));
		}
		else {
			try {
				measuredAt = Instant.parse(body.measuredAt());
			}
			catch (DateTimeParseException ex) {
				errors.add(new FieldError("measuredAt", "INVALID_FORMAT", "ISO-8601 zaman bekleniyor"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		service.measure(subject, operationId, new LocationService.Measurement(body.lat(), body.lon(), body.accuracy(), measuredAt));
		return ResponseEntity.noContent().build();
	}

	/** LC-04. */
	@GetMapping("/api/v1/me/location-sharing")
	List<LocationService.SharingView> mine(Authentication authentication) {
		return service.mine(Subject.from(authentication).requireAndroid());
	}

	/** LC-05. */
	@GetMapping("/api/v1/operations/{operationId}/locations")
	ResponseEntity<LocationService.LocationList> list(@PathVariable UUID operationId,
			@RequestParam(required = false) UUID groupId, @RequestParam(required = false) String freshness,
			Authentication authentication) {
		Subject subject = Subject.from(authentication).requireManagementSession();
		if (freshness != null && !FRESHNESS.contains(freshness)) {
			throw ApiException.validation(List.of(new FieldError("freshness", "INVALID_VALUE", "LIVE, STALE veya LAST_KNOWN")));
		}
		return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
			.body(service.list(subject, operationId, groupId, freshness));
	}

}
