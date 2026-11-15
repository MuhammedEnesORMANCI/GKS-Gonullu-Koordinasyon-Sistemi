package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/** LD-54: tek seferlik ilk yönetici kurulumu (yalnızca test değerleriyle). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "gks.bootstrap.admin.email=Ilk.Yonetici@Example.org",
		"gks.bootstrap.admin.password=Kurulum-Parolasi-2026!" })
class AdminBootstrapTests {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AdminBootstrap bootstrap;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Test
	void createsSingleAdminRequiringPasswordChangeAndIsIdempotent(CapturedOutput output) {
		Map<String, Object> user = jdbc.sql("""
				select u.id, u.password_hash, u.must_change_password, r.granted_by, r.scope_type
				  from identity.users u join access.role_assignments r on r.user_id = u.id and r.role = 'ADM'
				 where u.email = 'ilk.yonetici@example.org'""").query().singleRow();

		assertThat(user.get("must_change_password")).isEqualTo(true);
		assertThat(user.get("granted_by")).isNull();
		assertThat(user.get("scope_type")).isEqualTo("ORGANIZATION");
		assertThat(passwordEncoder.matches("Kurulum-Parolasi-2026!", (String) user.get("password_hash"))).isTrue();

		bootstrap.run(new DefaultApplicationArguments());   // aktif ADM var: hiçbir şey yapılmaz

		assertThat(jdbc.sql("select count(*) from access.role_assignments where role = 'ADM'").query(Integer.class).single())
			.isEqualTo(1);
		assertThat(output.getAll()).doesNotContain("Kurulum-Parolasi-2026!").doesNotContain("ilk.yonetici@example.org");
	}

	/** Var olan bir hesap kendiliğinden ADM yapılmaz (yetki yükseltme riski). */
	@Test
	void existingAccountIsNeverPromoted() {
		jdbc.sql("update access.role_assignments set revoked_at = now(), revoke_reason = 'MANUAL' where role = 'ADM'").update();

		bootstrap.run(new DefaultApplicationArguments());

		assertThat(jdbc.sql("select count(*) from access.role_assignments where role = 'ADM' and revoked_at is null")
			.query(Integer.class).single()).isZero();
	}

}
