package org.gks.backend.organization;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OperationRepository extends JpaRepository<Operation, UUID> {

	/** OR-01: yeni operasyon {@code PLANNED} olarak, tek kuruma (A-01) eklenir. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			insert into organization.operations
			    (id, organization_id, name, description, state, planned_end_at, created_by, created_at, updated_at)
			values (:id, (select id from organization.organizations), :name, :description, 'PLANNED', :plannedEndAt,
			        :createdBy, :now, :now)""")
	int insert(@Param("id") UUID id, @Param("name") String name, @Param("description") String description,
			@Param("plannedEndAt") Instant plannedEndAt, @Param("createdBy") UUID createdBy, @Param("now") Instant now);

	/** Ad, açıklama ve planlanan bitişi yazar; operasyon kapalıysa 0 döner. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Operation o
			   set o.name = :name, o.description = :description, o.plannedEndAt = :plannedEndAt, o.updatedAt = :now
			 where o.id = :id and o.state not in :closedStates""")
	int updateDetails(@Param("id") UUID id, @Param("name") String name, @Param("description") String description,
			@Param("plannedEndAt") Instant plannedEndAt, @Param("now") Instant now,
			@Param("closedStates") Collection<OperationState> closedStates);

	/**
	 * Durum geçişi (DB §4.3: {@code UPDATE … WHERE id = ? AND state = :beklenen}); başka bir istek durumu
	 * değiştirdiyse 0 döner. {@code activated_at} yalnızca ilk etkinleştirmede yazılır.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Operation o
			   set o.state = :target,
			       o.activatedAt = case when :target = org.gks.backend.organization.OperationState.ACTIVE
			                            then coalesce(o.activatedAt, :now) else o.activatedAt end,
			       o.closedAt = :closedAt, o.closedBy = :closedBy, o.updatedAt = :now
			 where o.id = :id and o.state = :expected""")
	int transition(@Param("id") UUID id, @Param("expected") OperationState expected,
			@Param("target") OperationState target, @Param("now") Instant now,
			@Param("closedAt") Instant closedAt, @Param("closedBy") UUID closedBy);

}
