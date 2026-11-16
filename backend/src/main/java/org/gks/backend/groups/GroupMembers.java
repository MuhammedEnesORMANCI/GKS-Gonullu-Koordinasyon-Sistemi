package org.gks.backend.groups;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Diğer modüllerin (görev hedefleri, TD-16 yayın anlık görüntüsü) grup üyeliğini okuduğu açık arayüz (ADR-001).
 * Salt okumadır.
 */
public interface GroupMembers {

	/**
	 * İstenen gruplardan bu operasyonun <b>arşivlenmemiş</b> olanları ve her birinin aktif üyeleri (lider dahil).
	 * Başka operasyonun, arşivlenmiş veya var olmayan grup sonuçta yer almaz. Sıra istekteki grup sırasıdır; üyeler
	 * katılım zamanına göre sıralıdır.
	 */
	Map<UUID, List<UUID>> activeMembers(UUID operationId, Collection<UUID> groupIds);

	/** Grup adları (arşivlenmişler dahil); bilinmeyen kimlikler sonuçta yer almaz. */
	Map<UUID, String> names(Collection<UUID> groupIds);

}
