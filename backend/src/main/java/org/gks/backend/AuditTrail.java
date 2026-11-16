package org.gks.backend;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Denetim olayını yayınlar (ortak çekirdek; modüller access'e veya audit'e bağımlı olmadan kullanır). Yayın
 * eşzamanlıdır: dinleyici çağıranın işleminde çalışır ({@code MANDATORY}); işlem dışında çağrılırsa hata verir.
 */
@Component
public class AuditTrail {

	private final ApplicationEventPublisher events;
	private final Clock clock;

	AuditTrail(ApplicationEventPublisher events, Clock clock) {
		this.events = events;
		this.clock = clock;
	}

	public void record(UUID actorId, String actorBasis, String client, String action, String resourceType, UUID resourceId,
			UUID operationId, Map<String, Object> details) {
		events.publishEvent(new AuditEvent(actorId, actorBasis, client, action, resourceType, resourceId, operationId,
				details, clock.instant(), MDC.get(TraceIdFilter.MDC_KEY)));
	}

}
