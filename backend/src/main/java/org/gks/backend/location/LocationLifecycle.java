package org.gks.backend.location;

import org.gks.backend.access.ParticipationRevoked;
import org.gks.backend.organization.OperationClosed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Paylaşımın kendiliğinden durduğu durumlar (TD-18) aynı işlemde: operasyon kapanışı ({@code OPERATION_CLOSED}) ve
 * katılımın kaldırılması ({@code PARTICIPATION_REVOKED}). Saklama süresi dolan son konumlar saatlik işle silinir
 * (ADR-019).
 */
@Component
class LocationLifecycle {

	private static final Logger log = LoggerFactory.getLogger(LocationLifecycle.class);

	private final LocationService service;

	LocationLifecycle(LocationService service) {
		this.service = service;
	}

	@EventListener
	void on(OperationClosed event) {
		service.operationClosed(event.operationId(), event.at());
	}

	@EventListener
	void on(ParticipationRevoked event) {
		service.participationRevoked(event.operationId(), event.userId(), event.at());
	}

	@Scheduled(initialDelayString = "${gks.location.purge-interval:PT1H}", fixedDelayString = "${gks.location.purge-interval:PT1H}")
	void purge() {
		try {
			int deleted = service.purgeExpired();
			if (deleted > 0) {
				log.info("Saklama süresi dolan {} son konum silindi", deleted);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Son konum temizliği başarısız: {}", ex.getMessage());
		}
	}

}
