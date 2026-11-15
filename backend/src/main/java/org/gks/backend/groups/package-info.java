/**
 * Gruplar modülü: Operasyon grupları, üyelik, lider kuralı (K-32) ve toplanma noktası.
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Gruplar")
package org.gks.backend.groups;

import org.springframework.modulith.ApplicationModule;
