package org.gks.backend.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Push sağlayıcısı seçimi ({@code gks.notifications.push.provider}). Bu sürümde yalnızca {@code none}: Firebase
 * projesi henüz yok. FCM eklendiğinde ({@code fcm}) aynı {@link PushGateway} arayüzüyle buraya bağlanır; teslim
 * kuyruğu, işçi ve gelen kutusu değişmez. Bilinmeyen değer açılışta hata verir (sessizce "kapalı" sayılmaz).
 */
@Configuration(proxyBeanMethods = false)
class PushConfiguration {

	@Bean
	PushGateway pushGateway(@Value("${gks.notifications.push.provider:none}") String provider) {
		return switch (provider) {
			case "none" -> DisabledPushGateway.INSTANCE;
			default -> throw new IllegalStateException("Desteklenmeyen push sağlayıcısı: " + provider
					+ " (bu sürümde yalnızca 'none'; FCM ayrı aşamada eklenecek)");
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
