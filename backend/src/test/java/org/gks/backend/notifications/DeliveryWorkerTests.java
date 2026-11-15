package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.notifications.NotificationDirectories.PushDevices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PUSH teslim kuyruğu (DB §7.1, LD-40): gerçek tablolar, sahte sağlayıcı. Push sağlayıcısı uygulamada henüz yok;
 * bu test FCM eklendiğinde değişmeyecek kuyruk, kira ve yeniden deneme mantığını doğrular.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryWorkerTests {

	@Autowired
	NotificationStore store;

	@Autowired
	PushDevices devices;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcClient jdbc;

	final List<PushGateway.Message> sent = new ArrayList<>();
	final AtomicReference<PushGateway.Result> next = new AtomicReference<>(PushGateway.Result.sent("msg-1"));
	final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));

	DeliveryWorker worker;
	UUID user;
	UUID device;

	@BeforeEach
	void setUp() {
		PushGateway gateway = new PushGateway() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public Result send(Message message) {
				sent.add(message);
				return next.get();
			}
		};
		Clock clock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		worker = new DeliveryWorker(store, gateway, devices, tx, clock);
		// Bu testin kuyruğunu diğer testlerin olası satırlarından ayırmak için önce eskileri gönderilmiş say.
		jdbc.sql("update notifications.deliveries set status = 'FAILED', failed_at = now() where status = 'QUEUED'").update();
		user = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', 'Cihaz Sahibi', 'Cihaz')""").params(user, user + "@example.org").update();
		device = UUID.randomUUID();
		jdbc.sql("insert into identity.devices (id, user_id, installation_id, fcm_token) values (?, ?, ?, ?)")
			.params(device, user, "kurulum-" + device, "fcm-" + device).update();
	}

	@Test
	void sendsLeasedRowAndMarksSent() {
		UUID delivery = queued("TASK", "HIGH");

		assertThat(worker.drain("STANDARD")).isEqualTo(1);

		assertThat(status(delivery)).isEqualTo("SENT");
		assertThat(sent).singleElement().satisfies(m -> {
			assertThat(m.token()).isEqualTo("fcm-" + device);
			assertThat(m.type()).isEqualTo("TASK");
			assertThat(m.priority()).isEqualTo(PushGateway.Priority.HIGH);
			assertThat(m.collapseKey()).startsWith("task:");
		});
		assertThat(worker.drain("STANDARD")).as("gönderilen satır yeniden alınmaz").isZero();
	}

	@Test
	void transientFailureRetriesWithBackoffThenFails() {
		UUID delivery = queued("TASK", "HIGH");
		next.set(PushGateway.Result.transientFailure("UNAVAILABLE"));

		worker.drain("STANDARD");
		assertThat(status(delivery)).isEqualTo("QUEUED");
		assertThat(worker.drain("STANDARD")).as("bekleme süresi dolmadan alınmaz").isZero();

		for (int i = 0; i < 2; i++) {
			now.set(now.get().plus(Duration.ofMinutes(5)));
			worker.drain("STANDARD");
		}
		assertThat(sent).hasSize(DeliveryWorker.MAX_TRIES);
		assertThat(status(delivery)).isEqualTo("FAILED");
		assertThat(jdbc.sql("select error_code from notifications.deliveries where id = ?").param(delivery)
			.query(String.class).single()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void invalidTokenFailsAndDeactivatesDevice() {
		UUID delivery = queued("TASK", "HIGH");
		next.set(PushGateway.Result.invalidToken("UNREGISTERED"));

		worker.drain("STANDARD");

		assertThat(status(delivery)).isEqualTo("FAILED");
		assertThat(jdbc.sql("select active from identity.devices where id = ?").param(device).query(Boolean.class).single())
			.isFalse();
	}

	@Test
	void criticalQueueIsSeparateAndLeaseHidesRowsFromSecondWorker() {
		UUID critical = queued("ALERT", "CRITICAL");
		assertThat(worker.drain("STANDARD")).isZero();

		// Kira: satır alınmış ama sonuç yazılmadan önce ikinci işçi aynı satırı göremez.
		List<NotificationStore.LeasedDelivery> leased = tx.execute(s -> store.lease("CRITICAL", now.get(), Duration.ofSeconds(60), 10));
		assertThat(leased).extracting(NotificationStore.LeasedDelivery::id).containsExactly(critical);
		assertThat(worker.drain("CRITICAL")).isZero();

		// Kira biter (işçi çöktü): satır yeniden alınır ve gönderilir (en az bir kez).
		now.set(now.get().plus(Duration.ofMinutes(2)));
		assertThat(worker.drain("CRITICAL")).isEqualTo(1);
		assertThat(status(critical)).isEqualTo("SENT");
	}

	private UUID queued(String category, String priority) {
		UUID notification = UUID.randomUUID();
		String kind = category.equals("ALERT") ? "ALERT_SENT" : "TASK_ASSIGNED";
		jdbc.sql("""
				insert into notifications.notifications (id, category, kind, priority, title, source_type, source_id,
				                                         audience_expr, idempotency_key)
				values (?, ?, ?, ?, 'Başlık', ?, ?, '{}'::jsonb, ?)""")
			.params(notification, category, kind, priority, category.equals("ALERT") ? "OPERATION" : "TASK", UUID.randomUUID(),
					"worker-test:" + notification)
			.update();
		UUID delivery = UUID.randomUUID();
		jdbc.sql("""
				insert into notifications.deliveries (id, notification_id, user_id, device_id, channel, queue_class, available_at)
				values (?, ?, ?, ?, 'PUSH', ?, ?)""")
			.params(delivery, notification, user, device, priority.equals("CRITICAL") ? "CRITICAL" : "STANDARD",
					java.sql.Timestamp.from(now.get().minusSeconds(1)))
			.update();
		return delivery;
	}

	private String status(UUID delivery) {
		return jdbc.sql("select status from notifications.deliveries where id = ?").param(delivery).query(String.class).single();
	}

}
