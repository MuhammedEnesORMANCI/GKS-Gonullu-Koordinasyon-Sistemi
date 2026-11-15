/**
 * Bildirimler modülü: Hedef kitle çözümleme, öncelik kuyrukları, FCM adaptörü ve teslim kaydı (ADR-004).
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Bildirimler")
package org.gks.backend.notifications;

import org.springframework.modulith.ApplicationModule;
