package org.gks.backend;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Gerçek zamanlı akışa olay yayını (adı {@code realtime} paketiyle büyük/küçük harf duyarsız dosya sistemlerinde
 * çakışmasın diye {@code RealtimeBus}) (ortak çekirdek; üretici modüller realtime'a bağımlı olmadan kullanır, ADR-009).
 * Olaylar iş işlemi <b>tamamlandıktan sonra</b> akışa verilir; geri alınan işlemin olayı hiç gönderilmez. Veri
 * yalnızca kimlik, durum ve zaman taşır (§12.2 RT-K3): başlık, metin, koordinat veya kişisel veri konmaz.
 */
@Component
public class RealtimeBus {

	/** Kurum geneli konu (duyuru geçmişi; ADM). */
	public static final String ORGANIZATION_TOPIC = "organization";

	private final ApplicationEventPublisher events;
	private final Clock clock;

	RealtimeBus(ApplicationEventPublisher events, Clock clock) {
		this.events = events;
		this.clock = clock;
	}

	public static String userTopic(UUID userId) {
		return "user:" + userId;
	}

	public static String operationTopic(UUID operationId) {
		return "operation:" + operationId;
	}

	/** Konum olayları ayrı konudadır: yalnızca {@code location.view} (KOR) abone olur (K-15). */
	public static String locationTopic(UUID operationId) {
		return "location:" + operationId;
	}

	/** {@code operationId} kullanıcı konusundaki olaylarda {@code null}. */
	public void publish(String topic, String type, UUID operationId, Map<String, Object> data) {
		events.publishEvent(new TopicEvent(topic, type, operationId, data, clock.instant()));
	}

	/**
	 * Oturum(lar) sonlandı: açık akışlar {@code session.revoked} ile kapanır. {@code sessionId} verilirse yalnızca o
	 * oturum; aksi halde kullanıcının {@code exceptSessionId} dışındaki tüm oturumları.
	 */
	public void sessionsEnded(UUID userId, UUID sessionId, UUID exceptSessionId) {
		events.publishEvent(new SessionsEnded(userId, sessionId, exceptSessionId));
	}

	/**
	 * Yetki değişti: etkilenen abonelikler yeniden değerlendirilir; yetkisi düşen konu {@code subscription.revoked}
	 * alır. {@code userId == null}: tüm bağlantılar. {@code reason}: {@code PERMISSION_REVOKED},
	 * {@code OPERATION_CLOSED} (operasyon konusu koşulsuz düşer), {@code MEMBERSHIP_ENDED}.
	 */
	public void accessChanged(UUID userId, UUID operationId, String reason) {
		events.publishEvent(new AccessChanged(userId, operationId, reason));
	}

	public record TopicEvent(String topic, String type, UUID operationId, Map<String, Object> data, Instant at) {
		public TopicEvent {
			data = data == null ? Map.of() : Map.copyOf(data);
		}
	}

	public record SessionsEnded(UUID userId, UUID sessionId, UUID exceptSessionId) {
	}

	public record AccessChanged(UUID userId, UUID operationId, String reason) {
	}

}
