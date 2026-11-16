package org.gks.backend.alerts;

/** emergency-alert-design §1.2 (TD-03). */
enum RecipientState {
	QUEUED, SENT, DELIVERED, SEEN, RESPONDED, UNANSWERED, FAILED
}
