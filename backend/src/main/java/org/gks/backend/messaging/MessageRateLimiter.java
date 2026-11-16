package org.gks.backend.messaging;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kişi başına dakikalık mesaj sınırı (ADR-024; varsayılan 30). Bellek içi, tek örnek (ADR-017 gibi).
 */
@Component
class MessageRateLimiter {

	private static final long WINDOW_MILLIS = 60_000;

	private final int perMinute;
	private final Clock clock;
	private final Map<UUID, long[]> windows = new ConcurrentHashMap<>();

	MessageRateLimiter(@Value("${gks.messaging.rate-limit-per-minute:30}") int perMinute, Clock clock) {
		this.perMinute = perMinute;
		this.clock = clock;
	}

	/** @return izin verildiyse 0, aksi hâlde yeniden denemeye kadar saniye */
	long acquire(UUID userId) {
		long now = clock.millis();
		long[] w = windows.compute(userId, (k, old) -> old == null || now - old[0] >= WINDOW_MILLIS ? new long[] { now, 1 }
				: new long[] { old[0], old[1] + 1 });
		return w[1] <= perMinute ? 0 : Math.max(1, (w[0] + WINDOW_MILLIS - now + 999) / 1000);
	}

}
