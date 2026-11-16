package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Modül sınırlarını doğrular (ADR-001): döngüsel bağımlılık ve başka modülün iç paketine erişim CI'da hata verir.
 */
class ModularityTests {

	private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

	@Test
	void moduleBoundariesAreRespected() {
		modules.verify();
	}

	@Test
	void allTenApplicationModulesAreDetected() {
		assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
				.containsExactlyInAnyOrder("identity", "access", "organization", "groups", "tasks",
						"location", "alerts", "notifications", "realtime", "audit", "messaging");
	}

}
