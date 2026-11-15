/**
 * Gerçek zamanlı modülü: SSE bağlantıları ve konu aboneliği (ADR-005, ADR-009).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Gerçek zamanlı")
package org.gks.backend.realtime;

import org.springframework.modulith.ApplicationModule;
