package org.gks.backend.devseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Görev 25 demo verisi: yalnızca ekler (mevcut kayıt silinmez/değişmez), ikinci çalıştırma yeni kayıt eklemez, bildirim
 * kuyruğuna ve acil alarma dokunmaz; koruma üretim profilini ve uzak veritabanını reddeder.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "gks.demo.run-on-startup=false")
@ActiveProfiles("demo-seed")
class DemoSeedTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	static final String PASSWORD = "Tatbikat-Demo-2026!";

	@Autowired
	DemoSeeder seeder;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Test
	void seedsOnlyAddsIsIdempotentAndSendsNothing() {
		UUID admin = accounts.register(new NewUserAccount("demo-yonetici-" + UUID.randomUUID() + "@example.org",
				"Deprem-Tatbikati-2026", "Yönetici Kişi", "Yönetici", null));
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, 'ADM', 'ORGANIZATION', ?)")
			.params(UUID.randomUUID(), admin, ORGANIZATION_ID).update();
		String before = snapshot();
		int pushBefore = count("select count(*) from notifications.deliveries where channel = 'PUSH'");
		int alertsBefore = count("select count(*) from alerts.alerts");

		DemoSeeder.Summary first = seeder.seed(PASSWORD);
		assertThat(first.rowsInserted()).isGreaterThan(40);
		// Var olan satırlar aynı kaldı (yalnızca ekleme).
		assertThat(snapshotOf(before)).isEqualTo(before);

		DemoSeeder.Summary second = seeder.seed(PASSWORD);
		assertThat(second.usersCreated()).isZero();
		assertThat(second.rowsInserted()).isZero();

		assertThat(jdbc.sql("select name || ':' || state from organization.operations where id in (?, ?) order by name")
			.params(DemoSeeder.OP_ACTIVE, DemoSeeder.OP_PLANNED).query(String.class).list())
			.containsExactly("Ege Orman Yangını Müdahale Tatbikatı:PLANNED", "Marmara Deprem Tatbikatı 2026:ACTIVE");
		assertThat(count("select count(*) from identity.users where email like '%@demo.local'")).isEqualTo(8);
		assertThat(count("select count(*) from groups.groups where operation_id in ('" + DemoSeeder.OP_ACTIVE + "', '"
				+ DemoSeeder.OP_PLANNED + "') and mp_lat is not null")).isEqualTo(4);
		assertThat(count("select count(*) from messaging.messages m join messaging.channels c on c.id = m.channel_id where c.operation_id = '"
				+ DemoSeeder.OP_ACTIVE + "'")).isEqualTo(10);
		assertThat(count("select count(*) from location.last_locations where operation_id = '" + DemoSeeder.OP_ACTIVE + "'")).isEqualTo(6);
		assertThat(count("select count(*) from notifications.public_announcements where state = 'PUBLISHED' and title like '%Tatbikat%'"))
			.isGreaterThanOrEqualTo(1);
		// ADM her iki demo operasyonunda KOR.
		assertThat(count("select count(*) from access.role_assignments where user_id = '" + admin + "' and role = 'KOR' and scope_id in ('"
				+ DemoSeeder.OP_ACTIVE + "', '" + DemoSeeder.OP_PLANNED + "')")).isEqualTo(2);
		// Bildirim gönderilmedi, acil alarm oluşturulmadı.
		assertThat(count("select count(*) from notifications.deliveries where channel = 'PUSH'")).isEqualTo(pushBefore);
		assertThat(count("select count(*) from alerts.alerts")).isEqualTo(alertsBefore);
		assertThat(count("select count(*) from audit.audit_entries where action = 'DEMO_DATA_SEEDED'")).isEqualTo(1);
		// Demo hesabı parolasıyla argon2 özetiyle kaydedildi (parola düz metin saklanmaz).
		assertThat(jdbc.sql("select password_hash from identity.users where email = 'ayse.yildiz@demo.local'").query(String.class)
			.single()).doesNotContain(PASSWORD);
	}

	@Test
	void guardRejectsProductionProfilesRemoteDatabasesAndMissingConfirmation() {
		String local = "jdbc:postgresql://localhost:5432/gks";
		DemoSeedGuard.check(new String[] { "local", "demo-seed" }, local, true, PASSWORD);
		assertThatThrownBy(() -> DemoSeedGuard.check(new String[] { "local" }, local, true, PASSWORD)).hasMessageContaining("demo-seed");
		assertThatThrownBy(() -> DemoSeedGuard.check(new String[] { "prod", "demo-seed" }, local, true, PASSWORD))
			.hasMessageContaining("Üretim");
		assertThatThrownBy(() -> DemoSeedGuard.check(new String[] { "demo-seed" }, "jdbc:postgresql://db.kurum.gov.tr:5432/gks", true,
				PASSWORD)).hasMessageContaining("bu makinedeki");
		assertThatThrownBy(() -> DemoSeedGuard.check(new String[] { "demo-seed" }, local, false, PASSWORD))
			.hasMessageContaining("confirm-local");
		assertThatThrownBy(() -> DemoSeedGuard.check(new String[] { "demo-seed" }, local, true, "kisa")).hasMessageContaining("12");
		assertThat(DemoSeedGuard.host("jdbc:postgresql://127.0.0.1:5432/x")).isEqualTo("127.0.0.1");
	}

	@Test
	void idsAreDeterministicAndTimeOrdered() {
		assertThat(DemoSeeder.id("msg:a", 10)).isEqualTo(DemoSeeder.id("msg:a", 10));
		assertThat(DemoSeeder.id("msg:a", 10).version()).isEqualTo(7);
		List<UUID> ordered = List.of(DemoSeeder.id("x", 1), DemoSeeder.id("y", 2), DemoSeeder.id("z", 3));
		assertThat(ordered).isSorted();
	}

	private int count(String sql) {
		return jdbc.sql(sql).query(Integer.class).single();
	}

	/** Mevcut operasyon/kullanıcı/rol satırlarının özeti (demo öncesi). */
	private String snapshot() {
		return jdbc.sql("""
				select md5(coalesce(string_agg(x, '|' order by x), '')) from (
				  select id::text || name || state || updated_at::text as x from organization.operations
				  union all select id::text || email || status || display_name || password_hash from identity.users
				  union all select id::text || role || scope_id::text || coalesce(revoked_at::text, '') from access.role_assignments
				) s""").query(String.class).single();
	}

	/** Aynı özet, yalnızca demo öncesi var olan satırlar üzerinden. */
	private String snapshotOf(String ignored) {
		return jdbc.sql("""
				select md5(coalesce(string_agg(x, '|' order by x), '')) from (
				  select id::text || name || state || updated_at::text as x from organization.operations
				   where id not in (:a, :p)
				  union all select id::text || email || status || display_name || password_hash from identity.users
				   where email not like '%@demo.local'
				  union all select id::text || role || scope_id::text || coalesce(revoked_at::text, '') from access.role_assignments
				   where scope_id not in (:a, :p)
				) s""").param("a", DemoSeeder.OP_ACTIVE).param("p", DemoSeeder.OP_PLANNED).query(String.class).single();
	}

}
