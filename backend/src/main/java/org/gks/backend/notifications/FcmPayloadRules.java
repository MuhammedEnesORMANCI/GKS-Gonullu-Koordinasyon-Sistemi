package org.gks.backend.notifications;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.api.client.json.GenericJson;
import com.google.api.client.json.gson.GsonFactory;

/**
 * FCM HTTP v1 yük kuralları (ADR-022). firebase-admin'in göndereceği JSON'un aynısı üretilir ve denetlenir; kurala
 * aykırı yük gönderilmeden reddedilir. Dönen ihlallerde yalnızca alan adı ve neden vardır; belirteç ve ileti metni
 * yazılmaz.
 * <ul>
 * <li>{@code token}: boş olmayan, yalnızca {@code [A-Za-z0-9_:-]}, en fazla 4096 karakter</li>
 * <li>{@code data}: tüm değerler metin; anahtar boş değil ve ayrılmış değil ({@code from}, {@code notification},
 * {@code message_type}, {@code collapse_key}, {@code google}/{@code gcm} önekli); anahtar + değer toplamı ≤ 4096 bayt</li>
 * <li>{@code android.priority}: {@code high} ya da {@code normal} (küçük harf, yerel ayardan bağımsız)</li>
 * <li>{@code android.ttl}: {@code <saniye>[.kesir]s}, en fazla 28 gün</li>
 * <li>{@code android.collapse_key}: boş değil, en fazla 100 karakter (uygulama sınırı)</li>
 * </ul>
 */
final class FcmPayloadRules {

	static final int MAX_DATA_BYTES = 4096;
	static final long MAX_TTL_SECONDS = 28L * 24 * 3600;
	static final int MAX_COLLAPSE_KEY = 100;

	private static final Set<String> RESERVED = Set.of("from", "notification", "message_type", "collapse_key");
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_:\\-]{1,4096}");
	private static final Pattern TTL = Pattern.compile("(\\d+)(\\.\\d{1,9})?s");

	private FcmPayloadRules() {
	}

	static List<String> violations(com.google.firebase.messaging.Message message) {
		try {
			GsonFactory json = GsonFactory.getDefaultInstance();
			return violations(json.fromString(json.toString(message), GenericJson.class));
		}
		catch (java.io.IOException ex) {
			return List.of("ileti JSON'a çevrilemedi (" + ex.getClass().getSimpleName() + ")");
		}
	}

	static List<String> violations(Map<String, Object> message) {
		List<String> out = new ArrayList<>();
		Object token = message.get("token");
		if (!(token instanceof String t) || !TOKEN.matcher(t).matches()) {
			out.add("message.token: boş ya da geçersiz biçimde");
		}
		if (message.get("data") instanceof Map<?, ?> data) {
			int bytes = 0;
			for (Map.Entry<?, ?> e : data.entrySet()) {
				String key = String.valueOf(e.getKey());
				String lower = key.toLowerCase(Locale.ROOT);
				if (key.isBlank() || RESERVED.contains(lower) || lower.startsWith("google") || lower.startsWith("gcm")) {
					out.add("message.data: ayrılmış ya da boş anahtar '" + key + "'");
				}
				if (!(e.getValue() instanceof String value)) {
					out.add("message.data." + key + ": değer metin değil");
				}
				else {
					bytes += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
				}
			}
			if (bytes > MAX_DATA_BYTES) {
				out.add("message.data: " + bytes + " bayt, sınır " + MAX_DATA_BYTES);
			}
		}
		if (message.get("android") instanceof Map<?, ?> android) {
			Object priority = android.get("priority");
			if (priority != null && !"high".equals(priority) && !"normal".equals(priority)) {
				out.add("message.android.priority: '" + priority + "' geçersiz (high | normal)");
			}
			Object ttl = android.get("ttl");
			if (ttl != null) {
				var m = TTL.matcher(String.valueOf(ttl));
				if (!m.matches()) {
					out.add("message.android.ttl: '" + ttl + "' biçimi geçersiz (ör. 3600s)");
				}
				else if (Long.parseLong(m.group(1)) > MAX_TTL_SECONDS) {
					out.add("message.android.ttl: 28 günden uzun");
				}
			}
			Object collapse = android.get("collapse_key");
			if (collapse != null && (String.valueOf(collapse).isBlank() || String.valueOf(collapse).length() > MAX_COLLAPSE_KEY)) {
				out.add("message.android.collapse_key: boş ya da " + MAX_COLLAPSE_KEY + " karakterden uzun");
			}
		}
		return out;
	}

}
