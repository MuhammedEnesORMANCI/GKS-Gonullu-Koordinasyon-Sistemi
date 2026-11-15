package org.gks.backend.access;

import java.time.Instant;
import java.util.UUID;

/**
 * Katılım ({@code GON/OPERATION}) kaldırılıyor (AC-02, backend §6 N-10). Aynı işlemde, eşzamanlı olarak yayımlanır:
 * groups kişinin o operasyondaki aktif üyeliklerini {@code PARTICIPATION_REVOKED} ile sonlandırır (kişi bir grubun
 * aktif lideriyse {@code 409 LEADER_REQUIRED} fırlatır ve işlem geri alınır, K-32); tasks bekleyen atamaları
 * {@code REMOVED} yapar.
 */
public record ParticipationRevoked(UUID operationId, UUID userId, UUID revokedBy, Instant at) {
}
