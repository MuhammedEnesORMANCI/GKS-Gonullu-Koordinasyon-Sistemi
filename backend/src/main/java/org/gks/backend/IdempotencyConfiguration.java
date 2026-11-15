package org.gks.backend;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Idempotency altyapısı (§3.6): özet anahtarları ve saatlik temizlik ({@link IdempotencyCleanup}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
class IdempotencyConfiguration {
}
