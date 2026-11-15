package org.gks.backend.notifications;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.gks.backend.notifications.NotificationDirectories.PushDevices;
import org.gks.backend.notifications.NotificationStore.LeasedDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PUSH teslim kuyruğu işçisi (DB §7.1, ADR-004, LD-40). {@code CRITICAL} ve {@code STANDARD} ayrı zamanlanmış
 * görevlerdir; {@code CRITICAL} hiçbir zaman {@code STANDARD}'ı beklemez (zamanlayıcı havuzu ≥ 2, application.yaml).
 * Push sağlayıcısı yapılandırılmamışsa ({@link PushGateway#enabled()} = false) hiçbir şey yapmaz; zaten kuyruk satırı
 * da oluşmaz.
 * <p>
 * Yeniden deneme (communication.md §1.3, VS-14): geçici hatada üstel bekleme, en fazla {@value #MAX_TRIES} deneme;
 * kalıcı hata (geçersiz belirteç) cihazı pasifleştirir ve satırı {@code FAILED} yapar.
 */
@Component
class DeliveryWorker {

	private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

	static final int MAX_TRIES = 3;
	static final int BATCH = 100;
	static final Duration LEASE = Duration.ofSeconds(60);
	static final Duration BACKOFF_BASE = Duration.ofSeconds(5);

	private final NotificationStore store;
	private final PushGateway gateway;
	private final PushDevices devices;
	private final TransactionTemplate tx;
	private final Clock clock;

	DeliveryWorker(NotificationStore store, PushGateway gateway, PushDevices devices, TransactionTemplate tx, Clock clock) {
		this.store = store;
		this.gateway = gateway;
		this.devices = devices;
		this.tx = tx;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${gks.notifications.worker.critical-delay:PT1S}")
	void critical() {
		drain("CRITICAL");
	}

	@Scheduled(fixedDelayString = "${gks.notifications.worker.standard-delay:PT5S}")
	void standard() {
		drain("STANDARD");
	}

	/** @return bu turda işlenen satır sayısı */
	int drain(String queueClass) {
		if (!gateway.enabled()) {
			return 0;
		}
		List<LeasedDelivery> batch = tx.execute(status -> store.lease(queueClass, clock.instant(), LEASE, BATCH));
		if (batch == null) {
			return 0;
		}
		for (LeasedDelivery delivery : batch) {
			PushGateway.Result result = send(delivery);
			tx.executeWithoutResult(status -> apply(delivery, result));
		}
		return batch.size();
	}

	private PushGateway.Result send(LeasedDelivery delivery) {
		if (delivery.sendTries() > MAX_TRIES) {
			return PushGateway.Result.rejected("TRANSIENT_EXHAUSTED");   // kira tekrarları (çökme) sınırı aştı
		}
		Optional<String> token = devices.token(delivery.deviceId());
		if (token.isEmpty()) {
			return PushGateway.Result.rejected("NO_ACTIVE_DEVICE");
		}
		try {
			return gateway.send(message(delivery, token.get()));
		}
		catch (RuntimeException ex) {
			// Sağlayıcı hatası ayrıntısı (belirteç içerebilir) günlüğe yazılmaz.
			log.warn("Push gönderimi başarısız: {}", ex.getClass().getSimpleName());
			return PushGateway.Result.transientFailure("PROVIDER_EXCEPTION");
		}
	}

	private void apply(LeasedDelivery delivery, PushGateway.Result result) {
		Instant now = clock.instant();
		switch (result.outcome()) {
			case SENT -> store.markSent(delivery.id(), result.messageId(), now);
			case TRANSIENT_FAILURE -> {
				if (delivery.sendTries() >= MAX_TRIES) {
					store.markFailed(delivery.id(), result.errorCode(), now);
				}
				else {
					store.retryAt(delivery.id(), result.errorCode(), now.plus(BACKOFF_BASE.multipliedBy(1L << delivery.sendTries())));
				}
			}
			case INVALID_TOKEN -> {
				store.markFailed(delivery.id(), result.errorCode(), now);
				devices.deactivate(delivery.deviceId(), "FCM_INVALID_TOKEN");
			}
			case REJECTED -> store.markFailed(delivery.id(), result.errorCode(), now);
		}
	}

	/** communication.md §1.5: {@code type} kategoriden, {@code collapse_key} {@code tür:{kimlik}} biçiminde. */
	static PushGateway.Message message(LeasedDelivery d, String token) {
		String type = switch (d.category()) {
			case "TASK" -> "TASK";
			case "MEETING_POINT" -> "MEETING_POINT";
			case "OPERATION_INVITE" -> "OPERATION_INVITE";
			case "ALERT" -> "ALERT";
			default -> "ANNOUNCEMENT";
		};
		String collapseKey = d.sourceId() == null ? null : d.sourceType().toLowerCase(Locale.ROOT) + ":" + d.sourceId();
		PushGateway.Priority priority = "NORMAL".equals(d.priority()) || "LOW".equals(d.priority())
				? PushGateway.Priority.NORMAL : PushGateway.Priority.HIGH;
		return new PushGateway.Message(d.id(), token, type, collapseKey, priority, d.title(), d.sourceType(), d.sourceId());
	}

}
