/**
 * Yetki modülü: Kapsamlı rol atamaları, izin kataloğu ve can() politika motoru (ADR-002).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Yetki")
package org.gks.backend.access;

import org.springframework.modulith.ApplicationModule;
