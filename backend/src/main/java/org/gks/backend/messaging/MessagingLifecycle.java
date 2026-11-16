package org.gks.backend.messaging;

import java.time.Clock;
import java.time.Duration;

import org.gks.backend.organization.OperationClosed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operasyon kapanınca kanallar salt okunur olur; kapanıştan {@code gks.messaging.retention-after-close} (varsayılan
 * 90 gün) sonra kanallar ve mesajları silinir (ADR-024).
 */
@Component
class MessagingLifecycle {

	private static final Logger log = LoggerFactory.getLogger(MessagingLifecycle.class);

	private final MessagingStore store;
	private final Clock clock;
	private final Duration retention;

	MessagingLifecycle(MessagingStore store, Clock clock,
			@Value("${gks.messaging.retention-after-close:P90D}") Duration retention) {
		this.store = store;
		this.clock = clock;
		this.retention = retention;
	}

	@EventListener
	@Transactional
	void on(OperationClosed event) {
		store.closeOperation(event.operationId(), event.at());
	}

	@Scheduled(initialDelayString = "${gks.messaging.purge-interval:PT1H}", fixedDelayString = "${gks.messaging.purge-interval:PT1H}")
	@Transactional
	void purge() {
		int deleted = store.purgeClosedBefore(clock.instant().minus(retention));
		if (deleted > 0) {
			log.info("Saklama süresi dolan {} mesaj kanalı silindi", deleted);
		}
	}

}
