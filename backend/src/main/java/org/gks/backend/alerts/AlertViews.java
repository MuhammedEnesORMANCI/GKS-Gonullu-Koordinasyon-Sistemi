package org.gks.backend.alerts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.gks.backend.alerts.AlertStore.Counts;

/** §11.3 yanıt şemaları. */
final class AlertViews {

	private AlertViews() {
	}

	record Ref(UUID id, String displayName) {
	}

	record GroupRef(UUID id, String name) {
	}

	record PreviewRecipient(UUID id, String displayName, List<GroupRef> groups) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AlertPreview(UUID alertId, UUID operationId, String operationName, int version, String audienceHash,
			int recipientCount, List<PreviewRecipient> recipients, String message, String requestedAction, String priority,
			UUID correctsAlertId) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AlertView(UUID id, UUID operationId, String operationName, String state, int version, String message,
			String requestedAction, String priority, List<String> responseOptions, Ref createdBy, Instant createdAt,
			Ref sentBy, Instant sentAt, Integer recipientCount, Counts counts, boolean deliveryDelayed, Ref cancelledBy,
			Instant cancelledAt, Ref closedBy, Instant closedAt, String closeReason, UUID correctsAlertId,
			UUID correctedByAlertId) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AlertSummary(UUID id, UUID operationId, String operationName, String state, String message, String priority,
			Instant createdAt, Instant sentAt, Counts counts) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MyRecipient(UUID id, String state, String responseOption, Instant respondedAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MyAlertView(UUID id, UUID operationId, String operationName, String state, String message,
			String requestedAction, String priority, List<String> responseOptions, Instant sentAt, MyRecipient recipient) {
	}

	/** Zaman alanlarında {@code null} = "bilinmiyor" / olmadı; her zaman yazılır (AC-24.2, §3 istisnası). */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	record AlertRecipientView(UUID id, Ref user, GroupRef group, String state, int reminderCount, String responseOption,
			Instant sentAt, Instant deliveredAt, Instant seenAt, Instant respondedAt, Instant respondedAtDevice,
			String failureReason) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Page<T>(List<T> items, String nextCursor) {
	}

	record ResendResult(AlertView alert, int requeuedCount, int skippedCount) {
	}

}
