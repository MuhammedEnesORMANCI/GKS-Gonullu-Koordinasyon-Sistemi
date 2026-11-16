package org.gks.backend.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Diğer modüllerin kişi gösterimi için okuduğu açık arayüz. {@link #displayNames} {@code UserRef} alanlarını verir
 * (her izdüşümde güvenli en dar küme, backend-api-design.md §3.8); {@link #summaries} KOR/ADM izdüşümündeki
 * {@code UserSummary}'dir ve yalnızca o kapsamdaki uçlarda kullanılır.
 */
public interface UserDirectory {

	/** Bilinmeyen kimlikler sonuçta yer almaz. */
	Map<UUID, String> displayNames(Collection<UUID> userIds);

	/** ADR-026: durumu {@code ACTIVE} olan tüm hesaplar (kurum geneli alarm hedefi). */
	java.util.List<UUID> activeUserIds();

	/** Verilen kimliklerden durumu {@code ACTIVE} olanlar. */
	java.util.Set<UUID> activeAmong(Collection<UUID> userIds);

	/** Bilinmeyen kimlikler sonuçta yer almaz. */
	Map<UUID, UserSummary> summaries(Collection<UUID> userIds);

	/** §5 {@code UserSummary}, KOR (kendi operasyonu) / ADM izdüşümü (§3.8). Boş alan yazılmaz (§3.1). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UserSummary(UUID id, String displayName, String status, String fullName, String email, String phone) {
	}

}
