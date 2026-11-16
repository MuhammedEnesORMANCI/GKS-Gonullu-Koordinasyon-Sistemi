package org.gks.backend.access;

import java.util.UUID;

/**
 * Konum izinleri (K-15; ADR-019). {@code location.view}: yalnızca operasyonun KOR'u, kurumsal oturum kapısıyla — ADM
 * dahil değildir (ADR-015/018'in dışında; proje sahibi aksine karar vermedikçe gizlilik lehine). Paylaşım
 * ({@code location.share}) katılımcılıktan doğar (location modülünde {@link Participation} ile).
 */
public interface LocationAccess {

	Decision canView(Subject subject, UUID operationId);

}
