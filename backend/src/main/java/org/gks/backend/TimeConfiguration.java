package org.gks.backend;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Zaman hesapları enjekte edilen saatle yapılır; testler değiştirebilir (backend-api-design.md §12). */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
