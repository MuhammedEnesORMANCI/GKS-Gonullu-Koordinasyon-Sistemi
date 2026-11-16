package org.gks.backend.alerts;

/** emergency-alert-design §1.1. {@code SENT} kısa ara durumdur; gönderim aynı işlemde {@code OPEN} ile biter. */
enum AlertState {
	DRAFT, SENT, OPEN, CANCELLED, CLOSED
}
