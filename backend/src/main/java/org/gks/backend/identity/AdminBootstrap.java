package org.gks.backend.identity;

import java.util.UUID;

import org.gks.backend.access.RoleAssignments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tek seferlik ilk yönetici kurulumu (LD-54). Kurumda aktif ADM yoksa ve {@code GKS_BOOTSTRAP_ADMIN_EMAIL} ile
 * {@code GKS_BOOTSTRAP_ADMIN_PASSWORD} tanımlıysa bir hesap açılır ve ADM/ORGANIZATION ataması yapılır
 * ({@code granted_by} boş, DB §4.1). Hesap {@code must_change_password = true} ile açılır; ADM olduğu için ilk web
 * girişinde TOTP de zorunludur (K-19). Aktif ADM varsa değişkenler yok sayılır. Parola ve e-posta günlüğe yazılmaz.
 * Hesap oluşturma ve rol ataması tek işlemdir; parola kuralına uymayan parola uygulamanın açılmasını durdurur.
 */
@Component
@EnableConfigurationProperties(AdminBootstrap.Properties.class)
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final Properties properties;
	private final RoleAssignments roles;
	private final UserAccounts accounts;
	private final Users users;
	private final TransactionTemplate transactions;

	AdminBootstrap(Properties properties, RoleAssignments roles, UserAccounts accounts, Users users,
			TransactionTemplate transactions) {
		this.properties = properties;
		this.roles = roles;
		this.accounts = accounts;
		this.users = users;
		this.transactions = transactions;
	}

	@ConfigurationProperties("gks.bootstrap.admin")
	record Properties(String email, String password, String fullName, String displayName) {

		boolean configured() {
			return email != null && !email.isBlank() && password != null && !password.isEmpty();
		}

		@Override
		public String toString() {
			return "AdminBootstrapProperties[configured=" + configured() + "]";
		}

	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.configured() || roles.hasActiveAdmin()) {
			return;
		}
		String email = UserAccounts.normalizeEmail(properties.email());
		if (users.findByEmail(email).isPresent()) {
			// Yetki yükseltme riski: var olan bir hesap kendiliğinden ADM yapılmaz.
			log.warn("İlk yönetici kurulumu atlandı: e-posta zaten kayıtlı; aktif yönetici yok, elle müdahale gerekir");
			return;
		}
		UUID userId = transactions.execute(status -> {
			UUID id = accounts.registerRequiringPasswordChange(new NewUserAccount(email, properties.password(),
					text(properties.fullName(), "Kurum Yöneticisi"), text(properties.displayName(), "Yönetici"), null));
			roles.grantInitialAdmin(id);
			return id;
		});
		log.info("İlk yönetici hesabı oluşturuldu (kullanıcı {}); ilk girişte parola değişikliği ve TOTP zorunlu", userId);
	}

	private static String text(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

}
