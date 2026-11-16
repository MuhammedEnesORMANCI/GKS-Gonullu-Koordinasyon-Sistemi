package org.gks.backend.alerts;

import org.gks.backend.organization.OperationClosed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Alarmın kendiliğinden ilerleyen kısmı: operasyon kapanışında açık alarmları kapatma (TD-06, aynı işlemde) ve
 * hatırlatma zamanlayıcısı (K-13: 2 dk × 5; ardından {@code UNANSWERED}). Bu aşamada hatırlatma yalnızca sayaç ve
 * durumu ilerletir; yeniden iletim FCM ile eklenecek (gelen kutusu kaydı zaten okunmamış durur, ADR-018).
 */
@Component
class AlertLifecycle {

	static final int BATCH = 500;

	private static final Logger log = LoggerFactory.getLogger(AlertLifecycle.class);

	private final AlertService service;

	AlertLifecycle(AlertService service) {
		this.service = service;
	}

	@EventListener
	void on(OperationClosed event) {
		service.closeForOperation(event.operationId(), event.closedBy());
	}

	@Scheduled(initialDelayString = "${gks.alerts.reminder-interval:PT30S}",
			fixedDelayString = "${gks.alerts.reminder-interval:PT30S}")
	void reminders() {
		try {
			int changed = service.advanceReminders(BATCH);
			if (changed > 0) {
				log.info("Alarm hatırlatma: {} alıcı yanıtsız sayıldı", changed);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Alarm hatırlatma turu başarısız: {}", ex.getMessage());
		}
	}

}
