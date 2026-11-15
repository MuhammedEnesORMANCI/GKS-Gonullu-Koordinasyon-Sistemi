package org.gks.backend.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Kayıtlı olmayan e-postalar için hatalı giriş sayacı (T-AUTH-04: "kayıtlı olmayan e-postada da aynı yanıt").
 * Kayıtlı hesapta sayaç veritabanındadır (DB E-16); burada aynı eşik ve süre bellekte uygulanır ki kilit davranışı
 * hesabın varlığını açık etmesin. Tek örnek varsayımı (§2.2). Kayıtlar 24 saat sonra, tablo dolunca en eskiden
 * başlayarak atılır.
 */
@Component
class UnknownAccountThrottle {

	static final int MAX_ENTRIES = 10_000;
	static final Duration RETENTION = Duration.ofHours(24);

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	private record Entry(int failures, Instant lastFailure, Instant lockedUntil) {
	}

	Optional<Instant> lockedUntil(String email, Instant now) {
		Entry entry = entries.get(email);
		return entry != null && entry.lockedUntil() != null && entry.lockedUntil().isAfter(now)
				? Optional.of(entry.lockedUntil()) : Optional.empty();
	}

	/** {@link Users#recordFailure} ile aynı kural: eşikte kilitlenir, süresi geçmiş kilitten sonra 1'den başlar. */
	Optional<Instant> recordFailure(String email, Instant now, Instant lockUntil) {
		if (entries.size() >= MAX_ENTRIES && !entries.containsKey(email)) {
			evict(now);
		}
		Entry updated = entries.compute(email, (key, current) -> {
			boolean lockExpired = current != null && current.lockedUntil() != null && !current.lockedUntil().isAfter(now);
			int failures = current == null || lockExpired ? 1 : current.failures() + 1;
			Instant lockedUntil = failures >= Users.LOCK_THRESHOLD ? lockUntil : null;
			return new Entry(failures, now, lockedUntil);
		});
		return Optional.ofNullable(updated.lockedUntil());
	}

	private void evict(Instant now) {
		entries.entrySet().removeIf(e -> e.getValue().lastFailure().plus(RETENTION).isBefore(now));
		while (entries.size() >= MAX_ENTRIES) {
			entries.entrySet().stream()
				.min(Comparator.comparing(e -> e.getValue().lastFailure()))
				.ifPresent(oldest -> entries.remove(oldest.getKey(), oldest.getValue()));
		}
	}

}
