package org.gks.backend.access;

import java.util.UUID;

/**
 * Konum izinleri (K-15; ADR-019, ADR-020). {@code location.view}: operasyonun KOR'u veya ADM (proje sahibi kararı
 * 16.11.2026, ADR-015 ile tutarlı), kurumsal oturum kapısıyla. Her görüntüleme denetime yazılır. Paylaşım
 * ({@code location.share}) katılımcılıktan doğar (location modülünde {@link Participation} ile).
 */
public interface LocationAccess {

	Decision canView(Subject subject, UUID operationId);

}
