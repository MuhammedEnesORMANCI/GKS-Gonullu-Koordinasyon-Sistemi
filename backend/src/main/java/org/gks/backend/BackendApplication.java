package org.gks.backend;

import java.util.Locale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		useRootCaseMapping();
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Harf dönüşümü ({@code toLowerCase()}/{@code toUpperCase()}) yerel ayardan bağımsız olsun: Türkçe yerel ayarda
	 * kütüphaneler "HIGH" → "hıgh" üretiyordu (firebase-admin, FCM 400, 16.11.2026). Tarih/sayı biçimi ve görüntüleme
	 * kategorileri sistemin yerel ayarında kalır. Uygulama kodu zaten her yerde {@link Locale#ROOT} kullanır.
	 */
	static void useRootCaseMapping() {
		Locale display = Locale.getDefault(Locale.Category.DISPLAY);
		Locale format = Locale.getDefault(Locale.Category.FORMAT);
		Locale.setDefault(Locale.ROOT);
		Locale.setDefault(Locale.Category.DISPLAY, display);
		Locale.setDefault(Locale.Category.FORMAT, format);
	}

}
