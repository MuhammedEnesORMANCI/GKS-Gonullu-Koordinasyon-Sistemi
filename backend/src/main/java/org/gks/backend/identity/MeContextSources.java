package org.gks.backend.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ID-11 {@code MeContext} için diğer modüllerden gelen bilgiler. organization ve groups identity'ye bağımlı olduğundan
 * identity onlara bağımlı olamaz (döngü); bu arayüzleri onlar gerçekleştirir (ADR-001).
 */
public final class MeContextSources {

	private MeContextSources() {
	}

	/** organization gerçekleştirir. */
	public interface Operations {

		/** Bilinmeyen kimlikler sonuçta yer almaz. */
		Map<UUID, OperationSummary> find(Collection<UUID> operationIds);

	}

	/** groups gerçekleştirir. */
	public interface Groups {

		/** Kullanıcının arşivlenmemiş gruplardaki aktif üyelikleri. */
		List<GroupMembership> activeMemberships(UUID userId);

	}

	/** {@code state} {@code OperationState} adıdır. */
	public record OperationSummary(UUID id, String name, String state) {
	}

	/** {@code role} {@code LEADER} veya {@code MEMBER}. */
	public record GroupMembership(UUID id, String name, UUID operationId, String role) {
	}

}
