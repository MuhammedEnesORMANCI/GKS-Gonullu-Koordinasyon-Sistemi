package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

/**
 * Acil alarm izinleri (authorization.md §4; ADR-018). {@code alert.send}, {@code alert.cancel}, {@code alert.close},
 * {@code alert.resend}, {@code alert.view}: operasyonun KOR'u <b>veya</b> ADM ({@link Grants#coordinates}); kurumsal
 * oturum kapısıyla (ADR-014). Yanıt ({@code alert.respond}) yetkiden değil alıcılıktan doğar (alarm modülünde).
 */
public interface AlertAccess {

	/** Bu operasyonda alarm oluşturma, gönderme, iptal, kapatma, yeniden gönderme ve izleme. */
	Decision canManage(Subject subject, UUID operationId);

	/** AL-16: izlenebilen operasyonlar. {@code all}: kurumdaki tüm operasyonlar (ADM). */
	ViewScope viewScope(Subject subject);

	record ViewScope(boolean all, Set<UUID> operationIds) {
		public ViewScope {
			operationIds = Set.copyOf(operationIds);
		}
	}

}
