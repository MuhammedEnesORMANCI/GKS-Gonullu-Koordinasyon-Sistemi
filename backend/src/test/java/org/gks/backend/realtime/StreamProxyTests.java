package org.gks.backend.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.gks.backend.RealtimeBus;
import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RAPOR_7 madde 2: canlı akış gerçek HTTP üzerinden, doğrudan ve araya giren bir TCP vekili (tünel benzeri) üzerinden
 * olay olay ulaşır. Bağlantı açıkken {@code message.created} birkaç saniye içinde gelmelidir; yanıt sıkıştırılmaz ve
 * vekillerin arabelleğe almaması için {@code Cache-Control: no-cache, no-transform} ile {@code X-Accel-Buffering: no}
 * gönderilir.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamProxyTests {

	@Autowired
	Environment environment;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	UserAccounts accounts;

	@Autowired
	RealtimeBus realtime;

	@Autowired
	TransactionTemplate transactions;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private Relay relay;

	private UUID user;

	private String token;

	@BeforeEach
	void setUp() throws IOException {
		user = accounts.register(new NewUserAccount("vekil-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				"Vekil Kişi", "Vekil", null));
		token = new TestSessions(jdbc, jwtEncoder).android(user);
		relay = new Relay(port());
	}

	@AfterEach
	void tearDown() throws IOException {
		relay.close();
	}

	@Test
	void eventsArriveOneByOneWhileConnectionIsOpenDirectly() throws Exception {
		assertLiveDelivery(port());
	}

	@Test
	void eventsArriveOneByOneThroughAProxy() throws Exception {
		assertLiveDelivery(relay.port());
	}

	private void assertLiveDelivery(int port) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/stream"))
			.header("Authorization", "Bearer " + token)
			.header("Accept", "text/event-stream")
			.header("Accept-Encoding", "gzip")
			.GET().build();
		HttpResponse<Stream<String>> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
			.get(10, TimeUnit.SECONDS);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache, no-transform");
		assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
		assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();

		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
		try {
			awaitLine(lines, ":bağlandı");   // abonelik kuruldu
			for (int i = 1; i <= 2; i++) {
				String messageId = UUID.randomUUID().toString();
				transactions.executeWithoutResult(status -> realtime.publish(RealtimeBus.userTopic(user), "message.created",
						null, Map.of("channelId", "kanal", "messageId", messageId)));
				awaitLine(lines, "event:message.created");
				awaitLine(lines, messageId);
			}
			assertThat(reader).isNotDone();   // olaylar bağlantı kapanmadan geldi (arabellek yok)
		}
		finally {
			reader.cancel(true);
			response.body().close();
		}
	}

	private static void awaitLine(BlockingQueue<String> lines, String fragment) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			String line = lines.poll(100, TimeUnit.MILLISECONDS);
			if (line != null && line.replace(" ", "").contains(fragment.replace(" ", ""))) {
				return;
			}
		}
		throw new AssertionError("5 sn içinde gelmedi: " + fragment);
	}

	private int port() {
		return Integer.parseInt(environment.getRequiredProperty("local.server.port"));
	}

	/** Baytları geldiği gibi ileten en basit TCP vekili (tünel benzeri; kendi arabelleği yok). */
	static final class Relay implements AutoCloseable {

		private final ServerSocket server;

		Relay(int target) throws IOException {
			server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try {
						Socket client = server.accept();
						Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
						pipe(client, upstream);
						pipe(upstream, client);
					}
					catch (IOException ex) {
						return;
					}
				}
			});
		}

		int port() {
			return server.getLocalPort();
		}

		private static void pipe(Socket from, Socket to) {
			Thread.ofVirtual().start(() -> {
				byte[] buffer = new byte[8192];
				try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
					int n;
					while ((n = in.read(buffer)) >= 0) {
						out.write(buffer, 0, n);
						out.flush();
					}
				}
				catch (IOException ex) {
					// bağlantı kapandı
				}
				finally {
					try {
						from.close();
						to.close();
					}
					catch (IOException ignored) {
						// kapanış
					}
				}
			});
		}

		@Override
		public void close() throws IOException {
			server.close();
		}

	}

}
