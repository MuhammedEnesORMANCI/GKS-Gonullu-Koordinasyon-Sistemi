package org.gks.backend;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Bir modülün ortak güvenlik zincirine katkısı (ör. identity'nin erişim belirteci filtresi). Kök yapılandırma
 * modül tiplerine bağımlı olmaz; böylece modül ↔ kök döngüsü oluşmaz (ADR-001).
 */
public interface SecurityContributor {

	void contribute(HttpSecurity http) throws Exception;

}
