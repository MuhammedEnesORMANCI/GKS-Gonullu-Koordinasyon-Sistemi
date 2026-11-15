/**
 * Acil alarm modülü: Alarm taslağı, onaylı gönderim, alıcı durumu, hatırlatma, iptal ve kapatma.
 * <p>
 * Kendi PostgreSQL şemasına sahiptir (TD-20). Diğer modüllerle yalnızca açık arayüzler
 * ve uygulama olayları üzerinden konuşur (ADR-001).
 */
@ApplicationModule(displayName = "Acil alarm")
package org.gks.backend.alerts;

import org.springframework.modulith.ApplicationModule;
