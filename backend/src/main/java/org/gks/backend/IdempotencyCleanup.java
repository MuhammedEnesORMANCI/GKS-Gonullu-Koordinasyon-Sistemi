package org.gks.backend;

import java.sql.Timestamp;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Süresi dolan idempotency satırlarının saatlik temizliği (§3.6 "Süre", DB §8.3). Doğruluk bu işe bağlı değildir:
 * arama {@code expires_at > now} koşuluyla yapılır ve süresi dolmuş satır istek işleminde silinir.
 */
@Component
class IdempotencyCleanup {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

	private final JdbcClient jdbc;
	private final Clock clock;

	IdempotencyCleanup(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
	int deleteExpired() {
		int deleted = jdbc.sql("delete from platform.idempotency_keys where expires_at <= :now")
			.param("now", Timestamp.from(clock.instant()))
			.update();
		if (deleted > 0) {
			log.info("Süresi dolan {} idempotency satırı silindi", deleted);
		}
		return deleted;
	}

}
