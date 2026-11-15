package org.gks.backend.access;

import java.util.List;
import java.util.UUID;

/**
 * Operasyona katılım (TD-01): kullanıcının operasyonda aktif {@code GON/OPERATION} ataması var mı (DB §4.1,
 * {@code ux_role_assignments_active}). Gruba yalnızca katılımcı eklenebilir (AC-09.7).
 */
public interface Participation {

	boolean isParticipant(UUID operationId, UUID userId);

	/** Operasyonun aktif katılımcıları (GON/OPERATION); duyurunun "tüm operasyon" hedefi. */
	List<UUID> participants(UUID operationId);

}
