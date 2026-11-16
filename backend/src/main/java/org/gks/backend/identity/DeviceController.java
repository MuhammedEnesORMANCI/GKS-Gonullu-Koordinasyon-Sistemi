package org.gks.backend.identity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID-26 {@code PUT /devices/{installationId}} (TD-17, ADR-022): Android kurulumunun FCM belirtecini kaydeder veya
 * yeniler. Yalnızca Android oturumu; kayıt bu oturuma bağlanır, böylece çıkışta (ID-06/07) cihaz pasifleşir ve
 * push gönderilmez. Belirteç yanıtta dönmez ve günlüğe yazılmaz.
 */
@RestController
class DeviceController {

	private static final Pattern INSTALLATION_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private final Devices devices;

	DeviceController(Devices devices) {
		this.devices = devices;
	}

	record DeviceRegisterRequest(String fcmToken, String appVersion, Boolean notificationsEnabled) {
		@Override
		public String toString() {
			return "DeviceRegisterRequest[***]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeviceView(String installationId, boolean active, Instant registeredAt, Instant lastSeenAt) {
	}

	@PutMapping("/api/v1/devices/{installationId}")
	ResponseEntity<DeviceView> register(@PathVariable String installationId, @RequestBody DeviceRegisterRequest body,
			Authentication authentication) {
		AuthenticatedSession session = AuthController.session(authentication);
		if (session.webSession()) {
			throw ApiException.of(Code.CLIENT_NOT_ALLOWED);
		}
		List<FieldError> errors = new ArrayList<>();
		if (!INSTALLATION_ID.matcher(installationId).matches()) {
			errors.add(new FieldError("installationId", "INVALID_FORMAT", "Kurulum kimliği geçersiz"));
		}
		if (body == null || body.fcmToken() == null || body.fcmToken().isBlank() || body.fcmToken().length() > 4096) {
			errors.add(new FieldError("fcmToken", "REQUIRED", "FCM belirteci zorunludur (en fazla 4096 karakter)"));
		}
		if (body == null || body.appVersion() == null || body.appVersion().isBlank() || body.appVersion().length() > 32) {
			errors.add(new FieldError("appVersion", "REQUIRED", "Uygulama sürümü zorunludur (en fazla 32 karakter)"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		Devices.Registration result = devices.register(session.userId(), session.sessionId(), installationId,
				body.fcmToken(), body.appVersion());
		DeviceView view = new DeviceView(installationId, true, result.registeredAt(), result.lastSeenAt());
		return result.created() ? ResponseEntity.status(201).body(view) : ResponseEntity.ok(view);
	}


}
