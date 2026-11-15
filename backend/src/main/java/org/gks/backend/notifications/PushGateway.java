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

	/** communication.md §1.5: data mesajı; yalnızca kimlikler ve kısa metin. */
	record Message(UUID deliveryId, String token, String type, String collapseKey, Priority priority, String title,
			String sourceType, UUID sourceId) {
		@Override
		public String toString() {
			return "PushMessage[delivery=" + deliveryId + ", type=" + type + "]";   // belirteç günlüğe yazılmaz
		}
	}

	enum Priority { HIGH, NORMAL }

	/**
	 * @param outcome    gönderim sonucu
	 * @param messageId  sağlayıcının ileti kimliği ({@code SENT})
	 * @param errorCode  sağlayıcı hata kodu (ör. {@code UNREGISTERED}, {@code UNAVAILABLE})
	 */
	record Result(Outcome outcome, String messageId, String errorCode) {
		public static Result sent(String messageId) {
			return new Result(Outcome.SENT, messageId, null);
		}

		public static Result transientFailure(String errorCode) {
			return new Result(Outcome.TRANSIENT_FAILURE, null, errorCode);
		}

		public static Result invalidToken(String errorCode) {
			return new Result(Outcome.INVALID_TOKEN, null, errorCode);
		}

		public static Result rejected(String errorCode) {
			return new Result(Outcome.REJECTED, null, errorCode);
		}
	}

	/** {@code TRANSIENT_FAILURE}: üstel beklemeyle en fazla 3 deneme (VS-14); {@code INVALID_TOKEN}: cihaz pasifleşir. */
	enum Outcome { SENT, TRANSIENT_FAILURE, INVALID_TOKEN, REJECTED }

}
