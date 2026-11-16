package org.gks.backend.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Operasyon kapandı ({@code COMPLETED}/{@code CANCELLED}). Kapanış işleminin <b>içinde</b>, eşzamanlı yayımlanır:
 * tüketiciler (ör. alerts: açık alarmlar {@code CLOSED}, {@code closeReason=OPERATION_CLOSED}, TD-06) aynı işlemde
 * çalışır; biri hata verirse kapanış geri alınır. Tasarımın outbox tüketicileri (§7.3) bu olayın yerini tutar.
 */
public record OperationClosed(UUID operationId, UUID closedBy, Instant at) {
}
