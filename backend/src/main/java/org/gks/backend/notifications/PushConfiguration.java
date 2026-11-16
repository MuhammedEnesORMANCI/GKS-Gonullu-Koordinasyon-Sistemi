package org.gks.backend.notifications;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Push sağlayıcısı seçimi ({@code gks.notifications.push.provider}): {@code none} (push kapalı, yalnızca gelen kutusu
 * ve SSE) veya {@code fcm} (ADR-022; hizmet hesabı anahtarının yolu {@code GKS_FIREBASE_CREDENTIALS_FILE}).
 * <p>
 * Push başlatılamazsa (okunamayan anahtar, eksik sınıf, bilinmeyen sağlayıcı) uygulama <b>çökmez</b>: hata günlüğe
 * açıkça yazılır, push kapalı ({@code none}) duruma düşer, gelen kutusu ve SSE çalışmaya devam eder. Durum
 * {@code /actuator/health} içinde {@code push} bileşeninde görünür: {@code UP} (FCM etkin), {@code DISABLED}
 * (yapılandırmada kapalı), {@code DEGRADED} (FCM istendi ama başlatılamadı). İkisi de genel durumu {@code UP}'tan
 * düşürmez (yaşam/hazırlık yoklamaları etkilenmez).
 */
@Configuration(proxyBeanMethods = false)
class PushConfiguration {

	private static final Logger log = LoggerFactory.getLogger(PushConfiguration.class);

	static final Status DISABLED = new Status("DISABLED");
	static final Status DEGRADED = new Status("DEGRADED");

	/** Başlatma sonucu: kullanılacak geçit ve sağlık durumu. {@code reason} gizli bilgi içermez. */
	record PushSetup(PushGateway gateway, String provider, Status status, String reason) {
	}

	@Bean
	PushSetup pushSetup(@Value("${gks.notifications.push.provider:none}") String provider,
			@Value("${gks.notifications.push.fcm.credentials-file:}") String credentialsFile) {
		return setup(provider, credentialsFile, FcmPushGateway::fromCredentialsFile);
	}

	@Bean
	PushGateway pushGateway(PushSetup setup) {
		return setup.gateway();
	}

	@Bean
	HealthIndicator pushHealthIndicator(PushSetup setup) {
		return () -> {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("provider", setup.provider());
			if (setup.reason() != null) {
				details.put("reason", setup.reason());
			}
			return Health.status(setup.status()).withDetails(details).build();
		};
	}

	static PushSetup setup(String provider, String credentialsFile, Function<String, PushGateway> fcmFactory) {
		String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
		if (p.isEmpty() || p.equals("none")) {
			return new PushSetup(DisabledPushGateway.INSTANCE, "none", DISABLED, null);
		}
		if (!p.equals("fcm")) {
			String reason = "Desteklenmeyen push sağlayıcısı: " + provider + " (none | fcm)";
			log.error("Push başlatılamadı, push kapalı (none) duruma düşürüldü: {}", reason);
			return new PushSetup(DisabledPushGateway.INSTANCE, provider, DEGRADED, reason);
		}
		try {
			return new PushSetup(fcmFactory.apply(credentialsFile), "fcm", Status.UP, null);
		}
		catch (RuntimeException | LinkageError ex) {
			// İleti yalnızca kendi metnimiz veya eksik sınıf adıdır; anahtar içeriği burada bulunmaz.
			String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			log.error("FCM başlatılamadı, push kapalı (none) duruma düşürüldü; uygulama gelen kutusu ve SSE ile çalışmaya devam ediyor. Neden: {}",
					reason);
			return new PushSetup(DisabledPushGateway.INSTANCE, "fcm", DEGRADED, reason);
		}
	}

	/** Sağlayıcı yok: PUSH kanalı kapalı. Uygulama içi bildirim (IN_APP) bundan etkilenmez. */
	enum DisabledPushGateway implements PushGateway {
		INSTANCE;

		@Override
		public boolean enabled() {
			return false;
		}

		@Override
		public Result send(Message message) {
			throw new IllegalStateException("Push sağlayıcısı yapılandırılmamış");
		}
	}

}
