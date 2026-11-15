package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

/**
 * Duyuru izinleri (authorization.md §4: {@code announcement.send.group} K1/KOR kendi operasyonu,
 * {@code announcement.send.organization} yalnızca K1/ADM). İkisi de kurumsal izindir: yalnızca MFA'lı web oturumu
 * ({@link Grants#corporate}); Android oturumu {@code CLIENT_NOT_ALLOWED} alır (ADR-013, DR-12).
 */
public interface AnnouncementAccess {

	/** NT-01/02: operasyon katılımcılarına veya gruplarına duyuru. */
	Decision canSendToOperation(Subject subject, UUID operationId);

	/** NT-01/02: kurum geneli duyuru (K-09). */
	Decision canSendToOrganization(Subject subject);

	/** NT-03/06: gönderen kapsamı. {@code organization}: kurum geneli dahil tümü (ADM). */
	ListScope listScope(Subject subject);

	record ListScope(boolean organization, Set<UUID> operationIds) {
		public ListScope {
			operationIds = Set.copyOf(operationIds);
		}

		public boolean isEmpty() {
			return !organization && operationIds.isEmpty();
		}
	}

}
