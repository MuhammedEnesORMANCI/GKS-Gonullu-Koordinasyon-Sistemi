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
	FcmPushGateway gateway;
	String appName;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
					+ exchange.getRequestHeaders().getFirst("Authorization"));
			exchange.getRequestBody().readAllBytes();
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
	}

	@Test
	void successReturnsMessageIdAndCallsV1EndpointWithBearer() {
		reply = new Reply(200, "{\"name\":\"projects/gks-test/messages/42\"}", false);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.SENT);
		assertThat(result.messageId()).isEqualTo("projects/gks-test/messages/42");
		assertThat(requests).containsExactly("POST /v1/projects/gks-test/messages:send Bearer sahte-erisim");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void unregisteredTokenKeepsHttpStatusAndFcmCode(boolean gzip) {
		reply = new Reply(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"), gzip);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(result.errorCode()).isEqualTo("UNREGISTERED");
		assertThat(result.httpStatus()).isEqualTo(404);
		assertThat(result.describe()).isEqualTo("UNREGISTERED (HTTP 404)");
	}

	@ParameterizedTest(name = "gzip={0}")
	@ValueSource(booleans = { true, false })
	void authAndPermissionErrorsAreRejectedWithStatusNotRetriedAsUnknown(boolean gzip) {
		reply = new Reply(401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\",\"status\":\"UNAUTHENTICATED\"}}", gzip);
		PushGateway.Result unauthenticated = gateway.send(message());
		assertThat(unauthenticated.outcome()).isEqualTo(PushGateway.Outcome.REJECTED);
		assertThat(unauthenticated.describe()).isEqualTo("FCM_UNAUTHENTICATED (HTTP 401)");

		reply = new Reply(403, "{\"error\":{\"code\":403,\"message\":\"Permission 'cloudmessaging.messages.create' denied\",\"status\":\"PERMISSION_DENIED\"}}", gzip);
		assertThat(gateway.send(message()).describe()).isEqualTo("FCM_PERMISSION_DENIED (HTTP 403)");

		reply = new Reply(403, fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH"), gzip);
		PushGateway.Result mismatch = gateway.send(message());
		assertThat(mismatch.outcome()).isEqualTo(PushGateway.Outcome.INVALID_TOKEN);
		assertThat(mismatch.describe()).isEqualTo("SENDER_ID_MISMATCH (HTTP 403)");
	}

	@Test
	void unavailableIsTransientWithStatus() {
		reply = new Reply(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE"), true);
		PushGateway.Result result = gateway.send(message());
		assertThat(result.outcome()).isEqualTo(PushGateway.Outcome.TRANSIENT_FAILURE);
		assertThat(result.describe()).isEqualTo("UNAVAILABLE (HTTP 503)");
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
