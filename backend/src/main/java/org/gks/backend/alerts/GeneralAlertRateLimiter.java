package org.gks.backend.alerts;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-026: kişi başına genel alarm gönderim sınırı (varsayılan 10 dakikada 3). Bellek içi, tek örnek (ADR-017 gibi).
 * Yanlışlıkla art arda gönderimi ve kötüye kullanımı sınırlar; olağan kullanımda sınıra yaklaşılmaz.
 */
@Component
class GeneralAlertRateLimiter {

	private final int limit;
	private final long windowMillis;
	private final Clock clock;
	private final Map<UUID, long[]> windows = new ConcurrentHashMap<>();

	GeneralAlertRateLimiter(@Value("${gks.alerts.general.rate-limit:3}") int limit,
			@Value("${gks.alerts.general.rate-window:PT10M}") Duration window, Clock clock) {
		this.limit = limit;
		this.windowMillis = window.toMillis();
		this.clock = clock;
	}

	/** @return izin verildiyse 0, aksi hâlde yeniden denemeye kadar saniye */
	long acquire(UUID userId) {
		long now = clock.millis();
		long[] w = windows.compute(userId, (k, old) -> old == null || now - old[0] >= windowMillis ? new long[] { now, 1 }
				: new long[] { old[0], old[1] + 1 });
		return w[1] <= limit ? 0 : Math.max(1, (w[0] + windowMillis - now + 999) / 1000);
	}

}
