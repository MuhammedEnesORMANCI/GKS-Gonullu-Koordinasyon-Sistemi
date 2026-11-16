package org.gks.backend.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.Ids;
import org.gks.backend.RealtimeBus;
import org.gks.backend.notifications.NotificationDirectories.PushDevices;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox'ın bu aşamadaki karşılığı (communication.md §1.3): bildirim, alıcı anlık görüntüsü ve teslim satırları
 * çağıranın iş işlemiyle <b>aynı işlemde</b> yazılır ({@code MANDATORY}). IN_APP teslimi böylece iş kaydıyla
 * atomiktir; PUSH satırları kuyruğa girer ve {@link DeliveryWorker} tarafından işlem dışında iletilir.
 */
@Component
class JdbcNotifier implements Notifier {

	private final NotificationStore store;
	private final PushGateway push;
	private final PushDevices devices;
	private final Clock clock;
	private final RealtimeBus realtime;

	JdbcNotifier(NotificationStore store, PushGateway push, PushDevices devices, Clock clock, RealtimeBus realtime) {
		this.realtime = realtime;
		this.store = store;
		this.push = push;
		this.devices = devices;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UUID> notify(NotificationRequest request) {
		if (request.recipients().isEmpty()) {
			return Optional.empty();
		}
		UUID id = Ids.newId();
		Instant now = clock.instant();
		if (!store.insertNotification(id, request, now)) {
			return Optional.empty();   // aynı kaynak olay: tekrar
		}
		store.insertInApp(id, request.recipients(), now);
		// RT-01: gelen kutusu canlı yenilenir (yalnızca kimlik; içerik REST ile çekilir). Gönderim işlem sonrasında.
		for (UUID recipient : request.recipients()) {
			realtime.publish(RealtimeBus.userTopic(recipient), "inbox.changed", null,
					Map.of("change", "CREATED", "notificationId", id));
		}
		// Push sağlayıcısı yoksa kuyruk satırı oluşturulmaz: "gönderildi" kaydı yalnızca gerçek iletimde oluşur.
		if (push.enabled()) {
			store.insertPush(id, devices.activeDevices(request.recipients()), queueClass(request.priority()), now);
		}
		return Optional.of(id);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void resurface(UUID notificationId, java.util.Collection<UUID> users) {
		if (users.isEmpty()) {
			return;
		}
		store.markUnread(notificationId, users);
		for (UUID user : users) {
			realtime.publish(RealtimeBus.userTopic(user), "inbox.changed", null,
					Map.of("change", "CREATED", "notificationId", notificationId));
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void pushOnly(PushOnlyRequest request) {
		if (request.recipients().isEmpty() || !push.enabled()) {
			return;   // push yoksa kayıt gereksiz: mesaj zaten kanalda ve SSE ile iletiliyor
		}
		UUID id = Ids.newId();
		Instant now = clock.instant();
		NotificationRequest row = new NotificationRequest(request.idempotencyKey(), request.category(), request.kind(),
				Priority.NORMAL, "Yeni mesaj", null, request.sourceType(), request.sourceId(), request.operationId(),
				request.refId() == null ? Map.of() : Map.of("ref", request.refId().toString()), request.recipients(),
				request.createdBy());
		if (store.insertNotification(id, row, now)) {
			store.insertPush(id, devices.activeDevices(request.recipients()), queueClass(Priority.NORMAL), now);
		}
	}

	/** ADR-004: {@code CRITICAL} ayrılmış kuyruk; diğerleri ortak kuyruk. */
	static String queueClass(Priority priority) {
		return priority == Priority.CRITICAL ? "CRITICAL" : "STANDARD";
	}

}
