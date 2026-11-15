package org.gks.backend;

import java.security.SecureRandom;
import java.util.UUID;

/** Uygulamada üretilen UUIDv7 kimlikleri (RFC 9562): 48 bit milisaniye + 74 bit rastgele. */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static UUID newId() {
		long millis = System.currentTimeMillis();
		long mostSignificant = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
		long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(mostSignificant, leastSignificant);
	}

}
