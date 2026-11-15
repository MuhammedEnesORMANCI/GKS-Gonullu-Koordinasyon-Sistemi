/**
 * Operasyon modülü: Kurum ve operasyon yaşam döngüsü, operasyona katılım (TD-01).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Operasyon")
package org.gks.backend.organization;

import org.springframework.modulith.ApplicationModule;
