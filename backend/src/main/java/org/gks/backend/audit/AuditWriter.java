package org.gks.backend.audit;

import org.gks.backend.AuditEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Denetim olaylarını üretici işlemin içinde kaydeder (eşzamanlı, outbox yok). {@code MANDATORY}: işlem dışında
 * yayınlanan olay hata verir; yazma hatası iş işlemini de geri aldırır (kayıt kaybolmaz, kayıtsız işlem olmaz).
 */
@Component
class AuditWriter {

	private final AuditStore store;

	AuditWriter(AuditStore store) {
		this.store = store;
	}

	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	void on(AuditEvent event) {
		store.insert(event);
	}

}
