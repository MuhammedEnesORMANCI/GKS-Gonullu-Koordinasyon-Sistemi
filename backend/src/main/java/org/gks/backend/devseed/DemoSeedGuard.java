package org.gks.backend.devseed;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Demo verisinin yalnızca geliştirme veritabanına eklenmesini sağlar. Kurallar: {@code demo-seed} profili açık,
 * üretim/hazırlık profili yok, veritabanı adresi bu makine (localhost/127.0.0.1/::1), açık onay
 * ({@code gks.demo.confirm-local=true}) ve en az 12 karakterli demo parolası.
 */
final class DemoSeedGuard {

	private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "prd", "staging", "stage", "live");
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

	private DemoSeedGuard() {
	}

	static void check(String[] profiles, String datasourceUrl, boolean confirmed, String password) {
		List<String> active = Arrays.stream(profiles).map(p -> p.toLowerCase(Locale.ROOT)).toList();
		if (!active.contains("demo-seed")) {
			throw new IllegalStateException("demo-seed profili açık değil");
		}
		for (String p : active) {
			if (FORBIDDEN_PROFILES.contains(p)) {
				throw new IllegalStateException("Üretim/hazırlık profilinde demo verisi eklenmez: " + p);
			}
		}
		if (!confirmed) {
			throw new IllegalStateException("gks.demo.confirm-local=true verilmedi");
		}
		String host = host(datasourceUrl);
		if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("Demo verisi yalnızca bu makinedeki veritabanına eklenir (adres: " + host + ")");
		}
		if (password == null || password.length() < 12) {
			throw new IllegalStateException("GKS_DEMO_PASSWORD en az 12 karakter olmalı");
		}
	}

	/** {@code jdbc:postgresql://host:port/db} → host. */
	static String host(String url) {
		if (url == null || !url.startsWith("jdbc:")) {
			return null;
		}
		try {
			return URI.create(url.substring(5)).getHost();
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
