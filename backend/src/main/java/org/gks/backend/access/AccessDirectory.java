package org.gks.backend.access;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * access'in kullanıcı ve kapsam bilgisini okuduğu arayüzler. identity ve organization zaten access'e bağımlı
 * olduğundan access onlara bağımlı olamaz (döngü); bu arayüzleri onlar gerçekleştirir (bağımlılığın tersine
 * çevrilmesi, ADR-001).
 */
public final class AccessDirectory {

	private AccessDirectory() {
	}

	/** identity gerçekleştirir. */
	public interface Users {

		Optional<UserInfo> find(UUID userId);

		Map<UUID, String> displayNames(Collection<UUID> userIds);

		/** Kurumdaki aktif hesaplar (ADM kurum geneli duyurusu, K-09). */
		List<UUID> activeUserIds();

	}

	/** organization gerçekleştirir. */
	public interface Scopes {

		/** MVP'de tek kurum (A-01). */
		UUID organizationId();

		/** Operasyonu çağıranın işleminde {@code FOR SHARE} ile kilitleyerek okur (eşzamanlı kapanışla yarış, E-9). */
		Optional<ScopeInfo> operationForShare(UUID operationId);

		Map<UUID, String> names(Collection<UUID> scopeIds);

	}

	public record UserInfo(UUID id, String displayName, boolean active) {
	}

	/** @param state operasyon durumu ({@code PLANNED}, {@code ACTIVE}, …); duyuru yalnızca {@code ACTIVE}'te (N-19) */
	public record ScopeInfo(UUID id, String name, boolean closed, String state) {
	}

}
