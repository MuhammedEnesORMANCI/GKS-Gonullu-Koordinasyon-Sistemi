package org.gks.backend.devseed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.AuditTrail;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo verisi (Görev 25): az ve gerçekçi, kurgusal, tatbikat adlı. <b>Hiçbir kayıt silinmez veya değiştirilmez</b>;
 * yalnızca eklenir. Kimlikler sabit (anahtar + sabit zamandan türetilmiş UUIDv7 biçiminde), her ekleme
 * {@code on conflict do nothing}; ikinci çalıştırma yeni kayıt eklemez. Bildirim kuyruğuna (PUSH) satır yazılmaz,
 * e-posta gönderilmez, acil alarm oluşturulmaz. Mevcut hesaplar yalnızca rollerine göre yeni operasyonlara eklenir:
 * kurum ADM'leri iki operasyonda KOR, bir operasyonda KOR olanlar aktif operasyonda KOR, gönüllüler aktif operasyonda
 * katılımcı (hesap bilgileri değişmez).
 */
@Component
@Profile("demo-seed")
class DemoSeeder {

	/** Kimlik üretimi için sabit taban zaman: kimlikler çalıştırma zamanından bağımsızdır. */
	static final Instant BASE = Instant.parse("2026-11-16T06:00:00Z");

	static final UUID OP_ACTIVE = id("op:marmara", 0);
	static final UUID OP_PLANNED = id("op:ege", 1);

	record DemoUser(String key, String email, String fullName, String displayName) {
	}

	static final List<DemoUser> USERS = List.of(
			new DemoUser("ayse", "ayse.yildiz@demo.local", "Ayşe Yıldız", "Ayşe Y."),
			new DemoUser("mehmet", "mehmet.kara@demo.local", "Mehmet Kara", "Mehmet K."),
			new DemoUser("zeynep", "zeynep.aydin@demo.local", "Zeynep Aydın", "Zeynep A."),
			new DemoUser("can", "can.ozkan@demo.local", "Can Özkan", "Can Ö."),
			new DemoUser("elif", "elif.sari@demo.local", "Elif Sarı", "Elif S."),
			new DemoUser("burak", "burak.tekin@demo.local", "Burak Tekin", "Burak T."),
			new DemoUser("selin", "selin.korkmaz@demo.local", "Selin Korkmaz", "Selin K."),
			new DemoUser("emre", "emre.yalcin@demo.local", "Emre Yalçın", "Emre Y."));

	record Summary(int usersCreated, int rowsInserted) {
	}

	private final JdbcClient jdbc;
	private final UserAccounts accounts;
	private final AuditTrail audit;
	private final Clock clock;
	private int inserted;

	DemoSeeder(JdbcClient jdbc, UserAccounts accounts, AuditTrail audit, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	Summary seed(String password) {
		inserted = 0;
		Instant now = clock.instant();
		UUID org = jdbc.sql("select id from organization.organizations").query(UUID.class).single();
		List<UUID> admins = existingWithRole("ADM", "ORGANIZATION");
		if (admins.isEmpty()) {
			throw new IllegalStateException("Kurumda ADM yok; demo operasyonlarının oluşturanı olacak hesap gerekli");
		}
		UUID admin = admins.getFirst();
		boolean alreadySeeded = jdbc.sql("select count(*) from organization.operations where id = ?").param(OP_ACTIVE)
			.query(Integer.class).single() > 0;
		// Mevcut hesaplar (demo dışı): koordinatörler ve gönüllüler; yalnızca ilk çalıştırmadaki roller esas alınır.
		List<UUID> coordinators = alreadySeeded ? List.of() : existingWithRole("KOR", "OPERATION");
		List<UUID> volunteers = alreadySeeded ? List.of() : existingWithRole("GON", "OPERATION");

		int created = 0;
		Map<String, UUID> u = new LinkedHashMap<>();
		for (DemoUser d : USERS) {
			Optional<UUID> existing = jdbc.sql("select id from identity.users where email = ?").param(d.email()).query(UUID.class)
				.optional();
			if (existing.isPresent()) {
				u.put(d.key(), existing.get());
			}
			else {
				u.put(d.key(), accounts.register(new NewUserAccount(d.email(), password, d.fullName(), d.displayName(), null)));
				created++;
			}
		}

		// ---- operasyonlar (Türkiye'de gerçekçi merkez ve alan; tatbikat adları)
		insert("""
				insert into organization.operations (id, organization_id, name, description, state, planned_end_at, activated_at,
				    created_by, created_at, updated_at, center_lat, center_lon, radius_m)
				values (?, ?, 'Marmara Deprem Tatbikatı 2026', ?, 'ACTIVE', ?, ?, ?, ?, ?, 40.7654, 29.9408, 3000)
				on conflict do nothing""", OP_ACTIVE, org,
				"Kocaeli İzmit merkezde arama kurtarma, barınma ve lojistik tatbikatı. Kurgusal senaryo: 6,8 büyüklüğünde deprem.",
				ts(now.plus(Duration.ofDays(2))), ts(at(5)), admin, ts(at(0)), ts(at(5)));
		insert("""
				insert into organization.operations (id, organization_id, name, description, state, planned_end_at, created_by,
				    created_at, updated_at, center_lat, center_lon, radius_m)
				values (?, ?, 'Ege Orman Yangını Müdahale Tatbikatı', ?, 'PLANNED', ?, ?, ?, ?, 38.1970, 26.8380, 5000)
				on conflict do nothing""", OP_PLANNED, org,
				"İzmir Seferihisar kırsalında orman yangını gözcülüğü ve köy tahliyesi tatbikatı.",
				ts(now.plus(Duration.ofDays(10))), admin, ts(at(1)), ts(at(1)));

		// ---- roller: KOR ve katılımcılar
		for (UUID a : admins) {
			role(a, "KOR", OP_ACTIVE, admin);
			role(a, "KOR", OP_PLANNED, admin);
		}
		for (UUID k : coordinators) {
			if (!admins.contains(k)) {
				role(k, "KOR", OP_ACTIVE, admin);
			}
		}
		List<UUID> activeParticipants = new ArrayList<>();
		for (String key : List.of("ayse", "mehmet", "zeynep", "can", "elif", "burak")) {
			activeParticipants.add(u.get(key));
		}
		for (UUID v : volunteers) {
			if (!admins.contains(v) && !coordinators.contains(v) && !u.containsValue(v) && isActive(v)) {
				activeParticipants.add(v);
			}
		}
		for (UUID p : activeParticipants) {
			role(p, "GON", OP_ACTIVE, admin);
		}
		for (String key : List.of("elif", "burak", "selin", "emre")) {
			role(u.get(key), "GON", OP_PLANNED, admin);
		}

		// ---- gruplar ve toplanma noktaları (koordinatlı)
		UUID gSearch = id("group:arama", 2);
		UUID gLogistics = id("group:lojistik", 3);
		UUID gWatch = id("group:gozcu", 4);
		UUID gEvac = id("group:tahliye", 5);
		group(gSearch, org, OP_ACTIVE, "Arama Kurtarma Ekibi 1", "B sektörü (Yahya Kaptan) enkaz alanları.", admin,
				"Yahya Kaptan Mahallesi park alanı, kuzey girişi", 40.7672, 29.9351, "Kask ve yeleklerle gelin.");
		group(gLogistics, org, OP_ACTIVE, "Lojistik ve Barınma", "Çadır kent kurulumu ve malzeme dağıtımı.", admin,
				"Kent meydanı toplanma alanı, saat kulesi önü", 40.7640, 29.9440, "Malzeme tırları buraya yanaşacak.");
		group(gWatch, org, OP_PLANNED, "Yangın Gözcü Ekibi", "Tepe noktalarında gözcülük ve telsiz iletişimi.", admin,
				"Orman işletme şefliği önü", 38.2010, 26.8420, null);
		group(gEvac, org, OP_PLANNED, "Tahliye Destek Ekibi", "Köy tahliyesinde yaşlı ve engelli vatandaşlara destek.", admin,
				"Köy kahvesi önü toplanma alanı", 38.1935, 26.8345, null);
		member(gSearch, u.get("ayse"), "LEADER", admin);
		member(gSearch, u.get("mehmet"), "MEMBER", admin);
		member(gSearch, u.get("zeynep"), "MEMBER", admin);
		for (UUID v : activeParticipants.subList(6, activeParticipants.size())) {
			member(gSearch, v, "MEMBER", admin);
		}
		member(gLogistics, u.get("can"), "LEADER", admin);
		member(gLogistics, u.get("elif"), "MEMBER", admin);
		member(gLogistics, u.get("burak"), "MEMBER", admin);
		member(gWatch, u.get("selin"), "LEADER", admin);
		member(gWatch, u.get("emre"), "MEMBER", admin);
		member(gEvac, u.get("burak"), "LEADER", admin);
		member(gEvac, u.get("elif"), "MEMBER", admin);

		// ---- görevler (farklı durumlarda)
		UUID t1 = id("task:kesif", 10);
		UUID t2 = id("task:dagitim", 11);
		UUID t3 = id("task:degerlendirme", 12);
		UUID t4 = id("task:nobet", 13);
		task(t1, OP_ACTIVE, "Enkaz alanı keşfi – B sektörü", "Hasarlı bina listesini güncelleyin, ses dinleme noktalarını işaretleyin.",
				"HIGH", "Yahya Kaptan Mahallesi park alanı", "PUBLISHED", admin, at(30), at(35));
		task(t2, OP_ACTIVE, "Su ve battaniye dağıtımı", "Çadır kentteki 40 aileye su ve battaniye dağıtımı.", "NORMAL",
				"Kent meydanı toplanma alanı", "IN_PROGRESS", admin, at(40), at(45));
		task(t3, OP_ACTIVE, "Tatbikat sonrası değerlendirme formu", "Ekip liderleri gözlemlerini forma yazacak.", "LOW", null,
				"DRAFT", admin, at(50), null);
		task(t4, OP_PLANNED, "Gözcü kulesi nöbet çizelgesi", "Üç vardiyalı nöbet çizelgesi hazırlanacak.", "NORMAL", null,
				"DRAFT", admin, at(55), null);
		assignment(t1, u.get("ayse"), gSearch, "ACCEPTED", admin, at(35), at(38));
		assignment(t1, u.get("mehmet"), gSearch, "ACCEPTED", admin, at(35), at(41));
		assignment(t1, u.get("zeynep"), gSearch, "PENDING", admin, at(35), null);
		assignment(t2, u.get("can"), gLogistics, "ACCEPTED", admin, at(45), at(47));
		assignment(t2, u.get("elif"), gLogistics, "ACCEPTED", admin, at(45), at(48));
		assignment(t2, u.get("burak"), gLogistics, "REJECTED", admin, at(45), at(50));
		jdbc.sql("update tasks.tasks set accepted_count = (select count(*) from tasks.task_assignments a where a.task_id = tasks.id "
				+ "and a.status = 'ACCEPTED') where id in (?, ?)").params(t1, t2).update();

		// ---- operasyon duyuruları (yalnızca gelen kutusu; PUSH kuyruğu yok)
		announcement(id("ann:brifing", 20), "Tatbikat brifingi 09.30'da",
				"Tüm ekipler 09.30'da kent meydanındaki koordinasyon çadırında toplansın. Kimlik kartlarınızı yanınızda bulundurun.",
				admin, at(60), activeParticipants);
		announcement(id("ann:hava", 21), "Hava durumu uyarısı",
				"Öğleden sonra sağanak bekleniyor. Yağmurluk ve yedek çorap getirin; enkaz alanında kaygan zemine dikkat.",
				admin, at(120), activeParticipants);

		// ---- mesaj kanalları ve doğal mesajlar
		UUID chOp = id("ch:op", 30);
		UUID chCoord = id("ch:coord", 31);
		UUID chSearch = id("ch:arama", 32);
		UUID chLog = id("ch:lojistik", 33);
		channel(chOp, OP_ACTIVE, null, "OPERATION");
		channel(chCoord, OP_ACTIVE, null, "COORDINATION");
		channel(chSearch, OP_ACTIVE, gSearch, "GROUP");
		channel(chLog, OP_ACTIVE, gLogistics, "GROUP");
		message(chOp, admin, "Günaydın, tatbikat başladı. Herkes toplanma noktasına ulaşınca ekibine yazsın.", 62);
		message(chOp, u.get("can"), "Lojistik ekibi meydanda, çadırlar kuruluyor.", 70);
		message(chOp, u.get("ayse"), "Arama ekibi parkta toplandı, keşfe çıkıyoruz.", 75);
		message(chSearch, u.get("ayse"), "B-12 binasının arka cephesinde ses dinleme yapacağız, sessizlik rica ediyorum.", 90);
		message(chSearch, u.get("mehmet"), "Anlaşıldı, kuzey köşede bekliyorum.", 92);
		message(chSearch, u.get("zeynep"), "İlk yardım çantası bende, gerekirse haber verin.", 95);
		message(chLog, u.get("can"), "Battaniyeler geldi, 3. sıradaki çadırlardan başlıyoruz.", 100);
		message(chLog, u.get("elif"), "Su kolileri yetersiz kalabilir, ikinci araç ne zaman gelir?", 104);
		message(chCoord, u.get("ayse"), "Arama ekibi: B sektöründe 4 binanın kontrolü tamamlandı.", 110);
		message(chCoord, u.get("can"), "Lojistik: dağıtım %60 tamamlandı, öğleden sonra bitiyor.", 112);

		// ---- gönüllülerin son konumları (konum paylaşımı açık; yalnızca KOR/ADM görür, görüntüleme denetlenir)
		double[][] spots = { { 40.7671, 29.9356 }, { 40.7676, 29.9349 }, { 40.7668, 29.9360 }, { 40.7642, 29.9437 },
				{ 40.7638, 29.9445 }, { 40.7645, 29.9431 } };
		int i = 0;
		for (String key : List.of("ayse", "mehmet", "zeynep", "can", "elif", "burak")) {
			location(u.get(key), OP_ACTIVE, spots[i][0], spots[i][1], 8 + i * 3, now.minus(Duration.ofMinutes(1 + i)), at(5));
			i++;
		}

		// ---- kamu duyuruları (yayında)
		publicAnnouncement(id("pub:tatbikat", 40), "Marmara Deprem Tatbikatı 2026 duyurusu",
				"İzmit merkezde kurgusal senaryolu bir deprem tatbikatı yapılacaktır. Tatbikat süresince bazı sokaklar kısa süreli "
						+ "kapatılabilir; sirenler tatbikat amaçlıdır. Gerçek acil durumlarda 112'yi arayın.", admin, at(15));
		publicAnnouncement(id("pub:canta", 41), "Kış öncesi afet çantası hatırlatması",
				"Afet çantanızda su, uzun ömürlü gıda, el feneri, yedek pil, ilk yardım malzemesi ve önemli belgelerin "
						+ "kopyaları bulunsun. Çantanızı yılda en az bir kez kontrol edin.", admin, at(16));

		if (inserted > 0 || created > 0) {
			// Aktör zorunlu (V15): demo operasyonlarının oluşturanı olan ADM, dayanak SYSTEM (geliştirme aracı).
			audit.record(admin, "SYSTEM", "SYSTEM", "DEMO_DATA_SEEDED", "OPERATION", OP_ACTIVE, OP_ACTIVE,
					Map.of("usersCreated", created, "rowsInserted", inserted));
		}
		return new Summary(created, inserted);
	}

	// ---- yardımcılar

	private List<UUID> existingWithRole(String role, String scope) {
		return jdbc.sql("""
				select distinct r.user_id from access.role_assignments r join identity.users u on u.id = r.user_id
				 where r.role = ? and r.scope_type = ? and r.revoked_at is null and u.status = 'ACTIVE'
				   and u.email not like '%@demo.local' order by r.user_id""")
			.params(role, scope).query(UUID.class).list();
	}

	private boolean isActive(UUID user) {
		return jdbc.sql("select count(*) from identity.users where id = ? and status = 'ACTIVE'").param(user)
			.query(Integer.class).single() > 0;
	}

	private void insert(String sql, Object... params) {
		inserted += jdbc.sql(sql).params(params).update();
	}

	private void role(UUID user, String role, UUID operation, UUID by) {
		insert("""
				insert into access.role_assignments (id, user_id, role, scope_type, scope_id, granted_by, granted_at)
				values (?, ?, ?, 'OPERATION', ?, ?, ?) on conflict do nothing""",
				id("role:" + user + ":" + role + ":" + operation, 3), user, role, operation, by, ts(at(4)));
	}

	private void group(UUID id, UUID org, UUID op, String name, String description, UUID by, String address, double lat,
			double lon, String note) {
		insert("""
				insert into groups.groups (id, organization_id, operation_id, name, description, created_by, mp_address, mp_lat,
				    mp_lon, mp_description, mp_time, mp_updated_by, mp_updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""",
				id, org, op, name, description, by, address, lat, lon, note, ts(at(180)), by, ts(at(6)));
	}

	private void member(UUID group, UUID user, String role, UUID by) {
		insert("""
				insert into groups.group_memberships (id, group_id, user_id, role, added_by, joined_at)
				values (?, ?, ?, ?, ?, ?) on conflict do nothing""",
				id("member:" + group + ":" + user, 7), group, user, role, by, ts(at(7)));
	}

	private void task(UUID id, UUID op, String title, String description, String priority, String meetingPoint, String state,
			UUID by, Instant created, Instant published) {
		insert("""
				insert into tasks.tasks (id, operation_id, title, description, priority, meeting_point, state, created_by, created_at,
				    updated_at, published_at, target_expr)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb) on conflict do nothing""",
				id, op, title, description, priority, meetingPoint, state, by, ts(created), ts(created),
				published == null ? null : ts(published));
	}

	private void assignment(UUID task, UUID user, UUID group, String status, UUID by, Instant assigned, Instant responded) {
		insert("""
				insert into tasks.task_assignments (id, task_id, user_id, via_group_id, status, assigned_at, assigned_by, respond_by,
				    responded_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""",
				id("asg:" + task + ":" + user, 11), task, user, group, status, ts(assigned), by,
				ts(assigned.plus(Duration.ofHours(24))), responded == null ? null : ts(responded),
				ts(responded == null ? assigned : responded));
	}

	private void announcement(UUID id, String title, String body, UUID by, Instant at, List<UUID> recipients) {
		int n = jdbc.sql("""
				insert into notifications.notifications (id, category, kind, priority, title, body, operation_id, audience_expr,
				    recipient_count, created_by, created_at, idempotency_key)
				values (?, 'ANNOUNCEMENT', 'ANNOUNCEMENT', 'NORMAL', ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?) on conflict do nothing""")
			.params(id, title, body, OP_ACTIVE, "{\"operation\": \"" + OP_ACTIVE + "\"}", recipients.size(), by, ts(at),
					"demo:" + id)
			.update();
		inserted += n;
		if (n == 0) {
			return;
		}
		for (UUID r : recipients) {
			insert("""
					insert into notifications.deliveries (id, notification_id, user_id, channel, queue_class, status, queued_at, sent_at)
					values (?, ?, ?, 'IN_APP', 'STANDARD', 'SENT', ?, ?) on conflict do nothing""",
					id("dlv:" + id + ":" + r, 21), id, r, ts(at), ts(at));
		}
	}

	private void channel(UUID id, UUID op, UUID group, String kind) {
		insert("""
				insert into messaging.channels (id, operation_id, group_id, kind, created_at) values (?, ?, ?, ?, ?)
				on conflict do nothing""", id, op, group, kind, ts(at(8)));
	}

	private void message(UUID channel, UUID sender, String body, int minute) {
		insert("""
				insert into messaging.messages (id, channel_id, sender_id, kind, body, created_at, client_key)
				values (?, ?, ?, 'USER', ?, ?, ?) on conflict do nothing""",
				id("msg:" + channel + ":" + minute, minute), channel, sender, body, ts(at(minute)), "demo-" + channel + "-" + minute);
	}

	private void location(UUID user, UUID op, double lat, double lon, double accuracy, Instant measured, Instant started) {
		insert("""
				insert into location.location_sharings (user_id, operation_id, state, started_at, last_sent_at, updated_at)
				values (?, ?, 'ACTIVE', ?, ?, ?) on conflict do nothing""", user, op, ts(started), ts(measured), ts(measured));
		insert("""
				insert into location.last_locations (user_id, operation_id, lat, lon, accuracy, measured_at, received_at)
				values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing""", user, op, lat, lon, accuracy, ts(measured), ts(measured));
	}

	private void publicAnnouncement(UUID id, String title, String body, UUID by, Instant at) {
		insert("""
				insert into notifications.public_announcements (id, title, body, state, created_by, created_at, updated_by, updated_at,
				    published_at)
				values (?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?) on conflict do nothing""", id, title, body, by, ts(at), by, ts(at), ts(at));
	}

	/** Sabit taban zamandan {@code minute} dakika sonrası. */
	static Instant at(int minute) {
		return BASE.plus(Duration.ofMinutes(minute));
	}

	private static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}

	/**
	 * Anahtardan belirlenimci, UUIDv7 biçiminde kimlik: zaman bölümü sabit tabandan ({@code minute} dakika sonrası) gelir,
	 * böylece demo kayıtları kendi aralarında doğru sıralanır ve her çalıştırmada aynı kimlik üretilir.
	 */
	static UUID id(String key, int minute) {
		byte[] hash;
		try {
			hash = MessageDigest.getInstance("SHA-256").digest(("gks-demo:" + key).getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		long millis = at(minute).toEpochMilli();
		long rand = 0;
		for (int i = 0; i < 8; i++) {
			rand = (rand << 8) | (hash[i] & 0xff);
		}
		long msb = (millis << 16) | 0x7000L | ((hash[8] & 0x0f) << 8) | (hash[9] & 0xff);
		long lsb = (rand & 0x3fffffffffffffffL) | 0x8000000000000000L;
		return new UUID(msb, lsb);
	}

}
