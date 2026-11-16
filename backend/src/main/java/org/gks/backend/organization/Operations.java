package org.gks.backend.organization;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Diğer modüllerin operasyonu okuduğu açık arayüz (ADR-001: başka modülün tablosuna doğrudan erişilmez).
 * Salt okumadır; operasyonu değiştirmez.
 */
public interface Operations {

	Optional<OperationRef> find(UUID id);

	/**
	 * Çağıranın işleminde operasyon satırını {@code FOR SHARE} ile kilitleyerek okur (DB §2.2 "kilit amaçlı okuma",
	 * E-9): eşzamanlı kapanış bu işlem bitene kadar bekler, böylece kapalı operasyona yazma yapılmaz.
	 */
	Optional<OperationRef> findForShare(UUID id);

	/** Tüm operasyonlar (kurum geneli görünümler: ADM mesaj kanalları, harita). Ad sırasıyla. */
	List<OperationRef> all();

	/** Liste görünümleri için operasyon adları; bilinmeyen kimlikler sonuçta yer almaz. */
	Map<UUID, String> names(Collection<UUID> ids);

	/** ADR-025: operasyon bölgeleri (merkez + yarıçap); bölgesi olmayanlar sonuçta yer almaz. */
	Map<UUID, AreaRef> areas(Collection<UUID> ids);

	record OperationRef(UUID id, UUID organizationId, String name, OperationState state) {
	}

	record AreaRef(double lat, double lon, Integer radiusM) {
	}

}
