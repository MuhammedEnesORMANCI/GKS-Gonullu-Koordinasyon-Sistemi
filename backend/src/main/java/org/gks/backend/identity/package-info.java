/**
 * Kimlik modülü: Kullanıcı hesabı, davet, parola, oturum ve yenileme belirteci, TOTP, cihaz ve FCM token kaydı.
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Kimlik")
package org.gks.backend.identity;

import org.springframework.modulith.ApplicationModule;
