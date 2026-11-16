package org.gks.backend.devseed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code demo-seed} profiliyle açılan uygulama: korumaları denetler, demo verisini ekler ve uygulamayı kapatır
 * (açık süreç kalmaz). Parola günlüğe yazılmaz.
 */
@Component
@Profile("demo-seed")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gks.demo.run-on-startup", havingValue = "true",
		matchIfMissing = true)
class DemoSeedRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

	private final DemoSeeder seeder;
	private final Environment environment;
	private final ConfigurableApplicationContext context;
	private final String datasourceUrl;
	private final boolean confirmed;
	private final String password;
	private final boolean exitAfter;

	DemoSeedRunner(DemoSeeder seeder, Environment environment, ConfigurableApplicationContext context,
			@Value("${spring.datasource.url:}") String datasourceUrl,
			@Value("${gks.demo.confirm-local:false}") boolean confirmed,
			@Value("${gks.demo.password:}") String password,
			@Value("${gks.demo.exit-after-seed:true}") boolean exitAfter) {
		this.seeder = seeder;
		this.environment = environment;
		this.context = context;
		this.datasourceUrl = datasourceUrl;
		this.confirmed = confirmed;
		this.password = password;
		this.exitAfter = exitAfter;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code = 0;
		try {
			DemoSeedGuard.check(environment.getActiveProfiles(), datasourceUrl, confirmed, password);
			DemoSeeder.Summary summary = seeder.seed(password);
			log.info("DEMO VERİSİ: {} yeni demo hesabı, {} yeni satır eklendi (0 = zaten eklenmiş).",
					summary.usersCreated(), summary.rowsInserted());
		}
		catch (RuntimeException ex) {
			log.error("DEMO VERİSİ EKLENEMEDİ: {}", ex.getMessage());
			code = 1;
		}
		if (exitAfter) {
			int exit = code;
			System.exit(SpringApplication.exit(context, () -> exit));
		}
		else if (code != 0) {
			throw new IllegalStateException("demo verisi eklenemedi");
		}
	}

}
