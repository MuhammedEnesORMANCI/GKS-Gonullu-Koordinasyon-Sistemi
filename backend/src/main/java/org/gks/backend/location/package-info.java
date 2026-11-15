/**
 * Konum modülü: Konum paylaşımı ve yalnızca son konum (K-14, K-16).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Konum")
package org.gks.backend.location;

import org.springframework.modulith.ApplicationModule;
