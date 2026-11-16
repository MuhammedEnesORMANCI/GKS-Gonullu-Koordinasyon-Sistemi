package org.gks.backend.organization;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code organization.operations} satırı (database-physical-design.md §4.3).
 * Değişiklikler durum koşullu {@code UPDATE} sorgularıyla yapılır ({@link OperationRepository});
 * varlık yalnızca okunur, böylece eşzamanlı bir durum geçişi üzerine yazılmaz.
 */
@Entity
@Table(schema = "organization", name = "operations")
class Operation {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OperationState state;

	@Column(name = "planned_end_at")
	private Instant plannedEndAt;

	@Column(name = "activated_at")
	private Instant activatedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "closed_by")
	private UUID closedBy;

	@Column(name = "close_reminder_sent_at")
	private Instant closeReminderSentAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "center_lat")
	private Double centerLat;

	@Column(name = "center_lon")
	private Double centerLon;

	@Column(name = "radius_m")
	private Integer radiusM;

	protected Operation() {
	}

	UUID id() { return id; }
	String name() { return name; }
	String description() { return description; }
	OperationState state() { return state; }
	Instant plannedEndAt() { return plannedEndAt; }
	Instant activatedAt() { return activatedAt; }
	Instant closedAt() { return closedAt; }
	Instant createdAt() { return createdAt; }
	OperationArea area() { return OperationArea.of(centerLat, centerLon, radiusM); }

}
