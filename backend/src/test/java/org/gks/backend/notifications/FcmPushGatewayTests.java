package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** ADR-022: FCM hata eşlemesi ve data yükü (ağ yok). */
class FcmPushGatewayTests {

	@Test
	void errorCodesMapToInvalidTokenTransientOrRejected() {
		assertThat(FcmPushGateway.classify("UNREGISTERED").outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		// INVALID_ARGUMENT yalnızca Google belirteci açıkça hedeflediğinde cihazı kapatır; aksi hâlde yük hatasıdır.
		assertThat(FcmPushGateway.classify("INVALID_ARGUMENT").outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(FcmPushGateway.classify("INVALID_ARGUMENT", true).outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(FcmPushGateway.classify("FCM_INVALID_ARGUMENT").outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(FcmPushGateway.classify("SENDER_ID_MISMATCH").outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(FcmPushGateway.classify("UNAVAILABLE").outcome()).isEqualTo(PushGateway.Outcome.TRANSIENT_FAILURE);
		assertThat(FcmPushGateway.classify("QUOTA_EXCEEDED").outcome()).isEqualTo(PushGateway.Outcome.TRANSIENT_FAILURE);
		assertThat(FcmPushGateway.classify("THIRD_PARTY_AUTH_ERROR").outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(FcmPushGateway.classify((String) null).outcome()).isEqualTo(PushGateway.Outcome.TRANSIENT_FAILURE);
	}

	@Test
	void dataOnlyMessageCarriesIdsAndShortTextButNotToken() {
		UUID alert = UUID.randomUUID();
		PushGateway.Message m = new PushGateway.Message(UUID.randomUUID(), "gizli-belirtec", "ALERT", "alert:" + alert,
				PushGateway.Priority.HIGH, "ACİL ALARM: Sel", "ALERT", alert, "ALERT_SENT", UUID.randomUUID(), "Tahliye", UUID.randomUUID());
		assertThat(FcmPushGateway.data(m)).containsEntry("type", "ALERT").containsEntry("kind", "ALERT_SENT")
			.containsEntry("sourceId", alert.toString()).containsEntry("priority", "HIGH").containsKeys("notificationId", "inboxItemId")
			.doesNotContainValue("gizli-belirtec");
		assertThat(FcmPushGateway.toFcm(m)).isNotNull();
		assertThat(m.toString()).doesNotContain("gizli-belirtec");
	}


	@Test
	void messagePayloadCarriesOnlyChannelAndMessageIdsNeverContent() {
		UUID channel = UUID.randomUUID();
		UUID message = UUID.randomUUID();
		PushGateway.Message m = new PushGateway.Message(UUID.randomUUID(), "gizli-belirtec", "MESSAGE", "channel:" + channel,
				PushGateway.Priority.NORMAL, "Yeni mesaj", "CHANNEL", channel, "MESSAGE_POSTED", UUID.randomUUID(),
				"Gizli içerik: 3. kat", null, message);
		assertThat(FcmPushGateway.data(m)).containsEntry("type", "MESSAGE").containsEntry("channelId", channel.toString())
			.containsEntry("messageId", message.toString()).containsEntry("priority", "NORMAL")
			.doesNotContainKeys("title", "body", "sourceType", "inboxItemId")
			.doesNotContainValue("Gizli içerik: 3. kat");
		assertThat(FcmPushGateway.toFcm(m)).isNotNull();
	}

}
