/**
 * Mesajlaşma modülü (ADR-024): operasyon, koordinasyon ve grup kanalları; birebir mesaj yoktur.
 * <p>
 * Kanal üyeliği saklanmaz: okuma/yazma hakkı her istekte rollerden ({@code access.ChannelAccess}), katılımdan ve grup
 * üyeliğinden ({@code groups.GroupMembers}) hesaplanır. Kendi PostgreSQL şemasına sahiptir ({@code messaging}).
 */
@ApplicationModule(displayName = "Mesajlaşma")
package org.gks.backend.messaging;

import org.springframework.modulith.ApplicationModule;
