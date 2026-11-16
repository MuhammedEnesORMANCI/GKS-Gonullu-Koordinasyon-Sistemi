package org.gks.backend.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.api.client.json.GenericJson;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Google hata gövdesinin ({@code {"error":{"message":…,"details":[…fieldViolations…]}}}) temizlenmiş özeti (ADR-022).
 * Çift tırnak içindeki her şey (Google'ın yankıladığı değerler: ileti metni, belirteç) {@code "…"} ile değiştirilir;
 * alan adları (tek tırnaklı) ve açıklamalar kalır. Uzunluk sınırlıdır.
 *
 * @param summary      ör. {@code message.android.priority: Invalid value …; Invalid JSON payload received.}
 * @param tokenProblem yanıt belirteci açıkça hedefliyor mu ({@code message.token} ihlali ya da "registration token")
 */
record FcmErrorDetail(String summary, boolean tokenProblem) {

	static final int MAX = 300;
	static final FcmErrorDetail NONE = new FcmErrorDetail(null, false);

	static FcmErrorDetail parse(String body) {
		if (body == null || body.isBlank()) {
			return NONE;
		}
		try {
			GenericJson root = GsonFactory.getDefaultInstance().fromString(body, GenericJson.class);
			if (!(root.get("error") instanceof Map<?, ?> error)) {
				return NONE;
			}
			List<String> parts = new ArrayList<>();
			boolean token = false;
			if (error.get("details") instanceof List<?> details) {
				for (Object d : details) {
					if (d instanceof Map<?, ?> detail && detail.get("fieldViolations") instanceof List<?> violations) {
						for (Object v : violations) {
							if (v instanceof Map<?, ?> violation) {
								String field = String.valueOf(violation.get("field"));
								token |= "message.token".equals(field);
								parts.add(field + ": " + String.valueOf(violation.get("description")));
							}
						}
					}
				}
			}
			String message = error.get("message") == null ? null : String.valueOf(error.get("message"));
			if (message != null) {
				token |= message.toLowerCase(Locale.ROOT).contains("registration token");
				parts.add(message);
			}
			return new FcmErrorDetail(sanitize(String.join("; ", parts)), token);
		}
		catch (Exception ex) {
			return new FcmErrorDetail("hata gövdesi çözülemedi (" + ex.getClass().getSimpleName() + ")", false);
		}
	}

	static String sanitize(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String clean = text.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("\\s+", " ").trim();
		return clean.length() > MAX ? clean.substring(0, MAX - 1) + "…" : clean;
	}

}
