package org.gks.backend;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Denetim olayı (K-26, backend-api-design.md §12.5). Üretici modül iş işleminin içinde yayınlar ({@link AuditTrail});
 * audit modülü aynı işlemde, eşzamanlı dinleyiciyle kaydeder. Kayıt yazılamazsa iş işlemi de geri alınır.
 *
 * @param actorBasis işlemin dayandığı yetki: {@code KOR}, {@code ADM} (ADR-015: KOR ataması olmadan), {@code TEK},
 *                   {@code UYM}, {@code LEADER}, {@code SELF}, {@code SYSTEM}
 * @param details    yapısal ayrıntı; mesaj içeriği, parola, belirteç, koordinat <b>yazılmaz</b> (AC-27.2)
 */
public record AuditEvent(UUID actorId, String actorBasis, String client, String action, String resourceType,
		UUID resourceId, UUID operationId, Map<String, Object> details, Instant at, String traceId) {

	public AuditEvent {
		details = details == null ? Map.of() : Map.copyOf(details);
	}

}
