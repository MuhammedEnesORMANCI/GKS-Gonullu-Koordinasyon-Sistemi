package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-022, 16.11.2026: FCM v1 yük kuralları. Kök neden: Türkçe yerel ayarda firebase-admin
 * {@code AndroidConfig.Priority.HIGH} için {@code "hıgh"} üretiyordu; FCM {@code INVALID_ARGUMENT (HTTP 400)}.
 */
class FcmPayloadRulesTests {

	Locale all;
	Locale format;
	Locale display;

	@BeforeEach
	void save() {
		all = Locale.getDefault();
		format = Locale.getDefault(Locale.Category.FORMAT);
		display = Locale.getDefault(Locale.Category.DISPLAY);
	}

	@AfterEach
	void restore() {
		Locale.setDefault(all);
		Locale.setDefault(Locale.Category.FORMAT, format);
		Locale.setDefault(Locale.Category.DISPLAY, display);
	}

	@Test
	void turkishLocaleBreaksAndroidPriorityAndRulesCatchItUntilCaseMappingIsFixed() {
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		assertThat(FcmPayloadRules.violations(FcmPushGateway.toFcm(alert("cihaz:Belirtec_1-2"))))
			.containsExactly("message.android.priority: 'hıgh' geçersiz (high | normal)");

		FcmPushGateway.ensureRootCaseMapping();
		assertThat("HIGH".toLowerCase()).isEqualTo("high");
		assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(Locale.forLanguageTag("tr-TR"));
		assertThat(FcmPayloadRules.violations(FcmPushGateway.toFcm(alert("cihaz:Belirtec_1-2")))).isEmpty();
	}

	@Test
	void realAlertAndAnnouncementPayloadsPass() {
		FcmPushGateway.ensureRootCaseMapping();
		assertThat(FcmPayloadRules.violations(FcmPushGateway.toFcm(alert("cihaz:Belirtec_1-2")))).isEmpty();
		var announcement = new PushGateway.Message(UUID.randomUUID(), "cihaz:Belirtec", "ANNOUNCEMENT", null,
				PushGateway.Priority.NORMAL, "Duyuru", "ANNOUNCEMENT", UUID.randomUUID(), "ANNOUNCEMENT_PUBLISHED",
				UUID.randomUUID(), "x".repeat(3000), UUID.randomUUID());
		assertThat(FcmPayloadRules.violations(FcmPushGateway.toFcm(announcement))).isEmpty();
	}

	@Test
	void reservedKeysNonStringValuesAndOversizedDataAreRejected() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("from", "a");
		data.put("notification", "a");
		data.put("message_type", "a");
		data.put("collapse_key", "a");
		data.put("google.sent_time", "a");
		data.put("gcm.n.e", "a");
		data.put("count", 3);
		assertThat(FcmPayloadRules.violations(Map.of("token", "t", "data", data)))
			.hasSize(7)
			.anyMatch(v -> v.contains("'from'"))
			.anyMatch(v -> v.contains("'google.sent_time'"))
			.anyMatch(v -> v.equals("message.data.count: değer metin değil"));
		assertThat(FcmPayloadRules.violations(Map.of("token", "t", "data", Map.of("body", "ş".repeat(2100)))))
			.containsExactly("message.data: 4204 bayt, sınır 4096");
	}

	@Test
	void androidTtlCollapseKeyAndTokenFormatAreChecked() {
		assertThat(FcmPayloadRules.violations(Map.of("token", "t", "android",
				Map.of("ttl", "3600", "priority", "HIGH", "collapse_key", "k".repeat(101)))))
			.containsExactlyInAnyOrder("message.android.ttl: '3600' biçimi geçersiz (ör. 3600s)",
					"message.android.priority: 'HIGH' geçersiz (high | normal)",
					"message.android.collapse_key: boş ya da 100 karakterden uzun");
		assertThat(FcmPayloadRules.violations(Map.of("token", "t", "android", Map.of("ttl", "2419201s"))))
			.containsExactly("message.android.ttl: 28 günden uzun");
		assertThat(FcmPayloadRules.violations(Map.of("token", "t", "android", Map.of("ttl", "3600.5s", "priority", "normal")))).isEmpty();
		for (String bad : new String[] { "", " abc", "abc ", "\"abc\"", "ab c" }) {
			assertThat(FcmPayloadRules.violations(Map.of("token", bad))).as(bad).containsExactly("message.token: boş ya da geçersiz biçimde");
		}
		assertThat(FcmPayloadRules.violations(Map.of())).containsExactly("message.token: boş ya da geçersiz biçimde");
	}

	@Test
	void violationsNeverContainTokenOrMessageText() {
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		var violations = FcmPayloadRules.violations(FcmPushGateway.toFcm(alert("gizli belirtec")));
		assertThat(String.join(" ", violations)).doesNotContain("gizli").doesNotContain("Tahliye").doesNotContain("Sel");
	}

	@Test
	void googleErrorBodyIsSummarizedWithoutEchoedValues() {
		String body = """
				{"error":{"code":400,"message":"Invalid value at 'message.android.priority' (type.googleapis.com/google.firebase.fcm.v1.AndroidMessagePriority), \\"hıgh\\"",
				 "status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[
				 {"field":"message.android.priority","description":"Invalid value at 'message.android.priority', \\"hıgh\\""}]}]}}""";
		FcmErrorDetail detail = FcmErrorDetail.parse(body);
		assertThat(detail.tokenProblem()).isFalse();
		assertThat(detail.summary()).startsWith("message.android.priority: Invalid value at 'message.android.priority'")
			.doesNotContain("hıgh").contains("\"…\"");

		FcmErrorDetail token = FcmErrorDetail.parse("""
				{"error":{"code":400,"message":"The registration token is not a valid FCM registration token","status":"INVALID_ARGUMENT"}}""");
		assertThat(token.tokenProblem()).isTrue();
		assertThat(FcmErrorDetail.parse("{\"error\":{\"details\":[{\"fieldViolations\":[{\"field\":\"message.token\",\"description\":\"x\"}]}]}}")
			.tokenProblem()).isTrue();
		assertThat(FcmErrorDetail.parse("<html>").summary()).startsWith("hata gövdesi çözülemedi");
		assertThat(FcmErrorDetail.parse(null).summary()).isNull();
	}

	private static PushGateway.Message alert(String token) {
		UUID alert = UUID.randomUUID();
		return new PushGateway.Message(UUID.randomUUID(), token, "ALERT", "alert:" + alert, PushGateway.Priority.HIGH,
				"ACİL ALARM: Sel", "ALERT", alert, "ALERT_SENT", UUID.randomUUID(), "Tahliye", UUID.randomUUID());
	}

}
