package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * ADR-022, 16.11.2026 hatası: FCM HTTP hata yanıtı (gzip başlıklı ya da başlıksız) gerçek firebase-admin istemcisi ve
 * üretimdeki taşıyıcı ({@link NetHttpTransport}) üzerinden çözülür; HTTP durumu ve FCM hata kodu kaybolmaz.
 * İstekler yerel sahte FCM sunucusuna yönlendirilir; ağa çıkılmaz (erişim belirteci sahte).
 */
class FcmTransportTests {

	record Reply(int status, String body, boolean gzip) {
	}

	HttpServer server;
	volatile Reply reply;
	final List<String> requests = new CopyOnWriteArrayList<>();
	final List<String> bodies = new CopyOnWriteArrayList<>();
	FcmPushGateway gateway;
	String appName;
	Locale savedLocale;
	Locale savedFormat;

	@BeforeEach
	void start() throws IOException {
		// Gerçek ortamdaki gibi Türkçe yerel ayar: harf dönüşümü düzeltilmezse android.priority "hıgh" gider.
		savedLocale = Locale.getDefault();
		savedFormat = Locale.getDefault(Locale.Category.FORMAT);
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
					+ exchange.getRequestHeaders().getFirst("Authorization"));
			bodies.add(exchange.getRequestHeaders().getFirst("Content-Encoding") + " | "
					+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
			byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
			if (reply.gzip()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
					gz.write(body);
				}
				body = out.toByteArray();
				exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			}
			exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
			exchange.sendResponseHeaders(reply.status(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		int port = server.getAddress().getPort();
		// Üretimdeki taşıyıcı; yalnızca bağlantı yerel sunucuya yönlendirilir.
		NetHttpTransport transport = new NetHttpTransport.Builder()
			.setConnectionFactory(url -> (HttpURLConnection) URI.create("http://127.0.0.1:" + port + url.getFile()).toURL().openConnection())
			.build();
		appName = "fcm-test-" + UUID.randomUUID();
		AccessToken token = new AccessToken("sahte-erisim", Date.from(Instant.now().plusSeconds(3600)));
		// 401 yanıtında istemci belirteci yeniler; sahte kimlik aynı belirteci döndürür (ağa çıkmaz).
		GoogleCredentials credentials = new GoogleCredentials(token) {
			@Override
			public AccessToken refreshAccessToken() {
				return token;
			}
		};
		gateway = FcmPushGateway.create(credentials, "gks-test", transport, appName);
	}

	@AfterEach
	void stop() {
		server.stop(0);
		FirebaseApp.getInstance(appName).delete();
		Locale.setDefault(savedLocale);
		Locale.setDefault(Locale.Category.FORMAT, savedFormat);
	}

	@Test
	void successReturnsMessageIdAndCallsV1EndpointWithBearer() {
		reply = new Reply(200, "{\"name\":\"projects/gks-test/messages/42\"}", false);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.SENT);
		assertThat(result.messageId()).isEqualTo("projects/gks-test/messages/42");
		assertThat(requests).containsExactly("POST /v1/projects/gks-test/messages:send Bearer sahte-erisim");
		// 16.11.2026 kök nedeni: Türkçe yerel ayarda "hıgh" gidiyordu (FCM 400).
		assertThat(bodies).singleElement().asString().contains("\"priority\":\"high\"").contains("\"ttl\":\"3600s\"")
			.doesNotContain("hıgh");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void unregisteredTokenKeepsHttpStatusAndFcmCode(boolean gzip) {
		reply = new Reply(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"), gzip);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(result.errorCode()).isEqualTo("UNREGISTERED");
		assertThat(result.httpStatus()).isEqualTo(404);
		assertThat(result.describe()).isEqualTo("UNREGISTERED (HTTP 404): hata");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void authAndPermissionErrorsAreRejectedWithStatusNotRetriedAsUnknown(boolean gzip) {
		reply = new Reply(401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\",\"status\":\"UNAUTHENTICATED\"}}", gzip);
		PushGateway.Result unauthenticated = gateway.send(message());
		assertThat(unauthenticated.outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(unauthenticated.describe()).startsWith("FCM_UNAUTHENTICATED (HTTP 401)");

		reply = new Reply(403, "{\"error\":{\"code\":403,\"message\":\"Permission 'cloudmessaging.messages.create' denied\",\"status\":\"PERMISSION_DENIED\"}}", gzip);
		assertThat(gateway.send(message()).describe())
			.isEqualTo("FCM_PERMISSION_DENIED (HTTP 403): Permission 'cloudmessaging.messages.create' denied");

		reply = new Reply(403, fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH"), gzip);
		PushGateway.Result mismatch = gateway.send(message());
		assertThat(mismatch.outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(mismatch.describe()).isEqualTo("SENDER_ID_MISMATCH (HTTP 403): hata");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void badRequestFieldViolationsAreLoggedAsDetailAndDoNotDeactivateDevice(boolean gzip) {
		reply = new Reply(400, """
				{"error":{"code":400,"message":"Invalid value at 'message.android.priority' (type.googleapis.com/google.firebase.fcm.v1.AndroidMessagePriority), \\"h\u0131gh\\"",
				 "status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[
				 {"field":"message.android.priority","description":"Invalid value at 'message.android.priority', \\"h\u0131gh\\""}]}]}}""", gzip);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(result.errorCode()).isEqualTo("FCM_INVALID_ARGUMENT");
		assertThat(result.describe()).startsWith("FCM_INVALID_ARGUMENT (HTTP 400): message.android.priority: Invalid value at 'message.android.priority'")
			.doesNotContain("cihaz-belirteci").doesNotContain("Tahliye");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void invalidArgumentAboutTheTokenDeactivatesDevice(boolean gzip) {
		reply = new Reply(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT")
			.replace("\"hata\"", "\"The registration token is not a valid FCM registration token\""), gzip);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(result.describe()).isEqualTo("INVALID_ARGUMENT (HTTP 400): The registration token is not a valid FCM registration token");
	}

	@Test
	void payloadViolatingRulesIsNotSent() {
		var bad = new PushGateway.Message(UUID.randomUUID(), "bozuk belirtec", "ALERT", "alert:1", PushGateway.Priority.HIGH,
				"ACİL ALARM", "ALERT", UUID.randomUUID(), "ALERT_SENT", UUID.randomUUID(), "Tahliye", UUID.randomUUID());
		PushGateway.Result result = gateway.send(bad);
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(result.describe()).isEqualTo("PAYLOAD_INVALID: message.token: boş ya da geçersiz biçimde");
		assertThat(requests).isEmpty();
	}

	@Test
	void unavailableIsTransientWithStatus() {
		reply = new Reply(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE"), true);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.TRANSIENT_FAILURE);
		assertThat(result.describe()).isEqualTo("UNAVAILABLE (HTTP 503): hata");
	}

	private static String fcmError(int code, String status, String fcmCode) {
		return """
				{"error":{"code":%d,"message":"hata","status":"%s","details":[
				{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"%s"}]}}""".formatted(code, status, fcmCode);
	}

	private static PushGateway.Message message() {
		return new PushGateway.Message(UUID.randomUUID(), "cihaz-belirteci", "ALERT", "alert:1", PushGateway.Priority.HIGH,
				"ACİL ALARM", "ALERT", UUID.randomUUID(), "ALERT_SENT", UUID.randomUUID(), "Tahliye", UUID.randomUUID());
	}

}
