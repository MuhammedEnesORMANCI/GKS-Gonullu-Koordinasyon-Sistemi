package org.gks.backend.access;

import java.util.UUID;

/**
 * Gerçek zamanlı konu abonelik kararları (RT-01, §12.2). Karar her bağlantı açılışında ve yetki değişikliği
 * olaylarında yeniden verilir. Kullanıcının kendi {@code user:{id}} konusu karar gerektirmez.
 */
public interface RealtimeAccess {

	/** {@code operation:{id}}: operasyonu koordine eden (KOR veya ADR-015 ile ADM), kurumsal oturum kapısıyla. */
	Decision canFollowOperation(Subject subject, UUID operationId);

	/** {@code location:{id}}: konum olayları operasyonun KOR'una ve ADM'ye (ADR-020). */
	Decision canFollowLocation(Subject subject, UUID operationId);

	/** {@code organization}: kurum geneli duyurular (ADM). */
	Decision canFollowOrganization(Subject subject);

}
