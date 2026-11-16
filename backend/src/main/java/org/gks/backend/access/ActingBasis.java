package org.gks.backend.access;

import java.util.UUID;

/**
 * Denetim kaydının {@code actor_basis} değeri: işlem hangi yetkiyle yapıldı. Karar kuralı {@link Grants#coordinates}
 * ile aynıdır: o operasyonun KOR'u ise {@code KOR}, değilse ADM ise {@code ADM} (ADR-015).
 */
public interface ActingBasis {

	/** Operasyon kapsamlı işlem: {@code KOR}, {@code ADM} veya (katılımcının kendi işlemi) {@code SELF}. */
	String operation(Subject subject, UUID operationId);

	/** Kurum kapsamlı işlem: {@code ADM}, {@code TEK}, {@code UYM} veya {@code SELF}. */
	String organization(Subject subject);

}
