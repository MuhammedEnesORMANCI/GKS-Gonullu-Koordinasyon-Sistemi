/**
 * Denetim modülü: Ekleme-yalnız denetim kayıtları (K-26).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Denetim")
package org.gks.backend.audit;

import org.springframework.modulith.ApplicationModule;
