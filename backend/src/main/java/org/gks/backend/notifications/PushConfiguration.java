package org.gks.backend.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Push sağlayıcısı seçimi ({@code gks.notifications.push.provider}): {@code none} (push kapalı, yalnızca gelen kutusu
 * ve SSE) veya {@code fcm} (ADR-022; hizmet hesabı anahtarının yolu {@code GKS_FIREBASE_CREDENTIALS_FILE}). Bilinmeyen
 * değer veya okunamayan anahtar dosyası açılışta hata verir (sessizce "kapalı" sayılmaz).
 */
@Configuration(proxyBeanMethods = false)
class PushConfiguration {

	@Bean
	PushGateway pushGateway(@Value("${gks.notifications.push.provider:none}") String provider,
			@Value("${gks.notifications.push.fcm.credentials-file:}") String credentialsFile) {
		return switch (provider) {
			case "none" -> DisabledPushGateway.INSTANCE;
			case "fcm" -> FcmPushGateway.fromCredentialsFile(credentialsFile);
			default -> throw new IllegalStateException("Desteklenmeyen push sağlayıcısı: " + provider + " (none | fcm)");
		};
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
