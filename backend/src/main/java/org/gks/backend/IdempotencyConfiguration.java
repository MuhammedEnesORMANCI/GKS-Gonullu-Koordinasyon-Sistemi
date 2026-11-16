package org.gks.backend;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Idempotency altyapısı (§3.6): özet anahtarları ve saatlik temizlik ({@link IdempotencyCleanup}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfiguration {

	/**
	 * Zamanlanmış işler (teslim işçisi, hatırlatmalar, temizlikler). {@code gks.scheduling.enabled=false} yalnızca
	 * demo verisi aracı içindir: araç çalışırken bildirim gönderilmez ve mevcut kayıtlara dokunulmaz.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "gks.scheduling.enabled",
			havingValue = "true", matchIfMissing = true)
	static class Scheduling {
	}
}
