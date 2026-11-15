/**
 * Görevler modülü: Görev, kişi bazlı atama ve kapasite takibi (K-12).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Görevler")
package org.gks.backend.tasks;

import org.springframework.modulith.ApplicationModule;
