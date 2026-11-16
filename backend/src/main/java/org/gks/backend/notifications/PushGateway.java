package org.gks.backend.notifications;

import java.util.UUID;

/**
 * Push kanal adaptörü (communication.md §1.4 "Kanal adaptörü arayüzü: send(delivery) → result"). MVP'de tek
 * uygulaması FCM olacaktır (ADR-004). Bir sağlayıcı yapılandırılmadığında {@link #enabled()} {@code false} döner:
 * bu durumda PUSH teslim satırı <b>hiç oluşturulmaz</b> ve teslim işçisi çalışmaz; sistem "gönderildi" kaydı
 * üretmez. Uygulama içi (IN_APP) teslim bundan bağımsız çalışır.
 */
public interface PushGateway {

	boolean enabled();

	/** Ağ çağrısı; veritabanı işlemi dışında yapılır (LD-40 kira deseni). */
	Result send(Message message);

	/**
	 * communication.md §1.5: yalnızca <b>data</b> mesajı (bildirimi uygulama kendisi oluşturur, ADR-022); kimlikler ve
	 * kısa metin. {@code notificationId}: SSE ile gelen aynı olayın tekilleştirme anahtarı; {@code inboxItemId}: alıcının
	 * gelen kutusu kaydı (bildirime dokununca açılan ayrıntı).
	 */
	record Message(UUID deliveryId, String token, String type, String collapseKey, Priority priority, String title,
			String sourceType, UUID sourceId, String kind, UUID notificationId, String body, UUID inboxItemId) {
		@Override
		public String toString() {
			return "PushMessage[delivery=" + deliveryId + ", type=" + type + "]";   // belirteç günlüğe yazılmaz
		}
	}

	enum Priority { HIGH, NORMAL }

	/**
	 * @param outcome    gönderim sonucu
	 * @param messageId  sağlayıcının ileti kimliği ({@code SENT})
	 * @param errorCode  sağlayıcı hata kodu (ör. {@code UNREGISTERED}, {@code UNAVAILABLE}, {@code FCM_UNAUTHENTICATED})
	 * @param httpStatus sağlayıcının HTTP durum kodu (yanıt alınamadıysa {@code null})
	 * @param detail     temizlenmiş ayrıntı (alan adları ve açıklama; belirteç, anahtar ve ileti metni içermez)
	 */
	record Result(Outcome outcome, String messageId, String errorCode, Integer httpStatus, String detail) {
		static final int MAX_DESCRIPTION = 500;

		public static Result sent(String messageId) {
			return new Result(Outcome.SENT, messageId, null, null, null);
		}

		public static Result transientFailure(String errorCode) {
			return new Result(Outcome.TRANSIENT_FAILURE, null, errorCode, null, null);
		}

		/** {@code errorCode}: {@code UNREGISTERED}, {@code SENDER_ID_MISMATCH} ya da belirteci açıkça hedefleyen {@code INVALID_ARGUMENT}. */
		public static Result invalidToken(String errorCode) {
			return new Result(Outcome.INVALID_TOKEN, null, errorCode, null, null);
		}

		public static Result rejected(String errorCode) {
			return new Result(Outcome.REJECTED, null, errorCode, null, null);
		}

		Result withHttpStatus(Integer status) {
			return new Result(outcome, messageId, errorCode, status, detail);
		}

		Result withDetail(String text) {
			return new Result(outcome, messageId, errorCode, httpStatus, text == null || text.isBlank() ? null : text);
		}

		/** Teslim satırına ve günlüğe yazılan özet, ör. {@code INVALID_ARGUMENT (HTTP 400): message.android.priority: …}. */
		String describe() {
			String text = errorCode + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")") + (detail == null ? "" : ": " + detail);
			return text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION - 1) + "…" : text;
		}
	}

	/** {@code TRANSIENT_FAILURE}: üstel beklemeyle en fazla 3 deneme (VS-14); {@code INVALID_TOKEN}: cihaz pasifleşir. */
	enum Outcome { SENT, TRANSIENT_FAILURE, INVALID_TOKEN, REJECTED }

}
