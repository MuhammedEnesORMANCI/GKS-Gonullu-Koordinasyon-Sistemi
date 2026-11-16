package org.gks.backend.notifications;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kimlik doğrulamasız uçlar için istemci (IP) başına sabit pencereli oran sınırı (ADR-023). Bellek içi, tek örnek
 * (SSE gibi, ADR-017); çoklu örnekte vekil/ağ geçidi düzeyinde sınır gerekir. Sınır aşılırsa {@code 429 RATE_LIMITED}.
 */
@Component
class PublicRateLimiter {

	static final Duration WINDOW = Duration.ofMinutes(1);
	private static final int MAX_TRACKED = 10_000;

	private final int perWindow;
	private final Clock clock;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	PublicRateLimiter(@Value("${gks.public.rate-limit-per-minute:60}") int perWindow, Clock clock) {
		this.perWindow = perWindow;
		this.clock = clock;
	}

	record Window(long start, int count) {
	}

	/** @return izin verildiyse 0, aksi hâlde yeniden denemeye kadar saniye. */
	long acquire(String client) {
		long now = clock.millis();
		if (windows.size() > MAX_TRACKED) {
			evict(now);
		}
		Window w = windows.compute(client == null ? "?" : client, (key, old) ->
				old == null || now - old.start() >= WINDOW.toMillis() ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
		if (w.count() <= perWindow) {
			return 0;
		}
		return Math.max(1, (w.start() + WINDOW.toMillis() - now + 999) / 1000);
	}

	private void evict(long now) {
		for (Iterator<Window> it = windows.values().iterator(); it.hasNext();) {
			if (now - it.next().start() >= WINDOW.toMillis()) {
				it.remove();
			}
		}
	}

}
