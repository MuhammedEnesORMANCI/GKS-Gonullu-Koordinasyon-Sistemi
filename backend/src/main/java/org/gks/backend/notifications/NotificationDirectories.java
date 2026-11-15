package org.gks.backend.notifications;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bildirim modülünün ihtiyaç duyduğu, sahibi başka modül olan bilgiler (bağımlılığın tersine çevrilmesi, ADR-001):
 * arayüz burada tanımlanır, ilgili modül uygular. Böylece notifications → groups/identity bağımlılığı ve
 * groups ↔ notifications döngüsü oluşmaz.
 */
public final class NotificationDirectories {

	private NotificationDirectories() {
	}

	/** identity uygular: push gönderilebilecek aktif cihazlar ({@code identity.devices}). */
	public interface PushDevices {

		/** kullanıcı → aktif cihaz kimlikleri */
		Map<UUID, List<UUID>> activeDevices(Collection<UUID> userIds);

		/** Aktif cihazın FCM belirteci; cihaz pasifse boş. */
		Optional<String> token(UUID deviceId);

		/** Kalıcı FCM hatası (geçersiz belirteç): cihaz pasifleşir (communication.md §1.3). */
		void deactivate(UUID deviceId, String reason);
	}

	/** groups uygular: duyuru hedef kitlesi için grup üyeleri. */
	public interface GroupAudience {

		/**
		 * @return yalnızca {@code operationId} operasyonunun arşivlenmemiş gruplarından istenenler: grup → aktif üyeler.
		 *         Haritada olmayan grup kapsam dışıdır.
		 */
		Map<UUID, List<UUID>> activeMembers(UUID operationId, Collection<UUID> groupIds);
	}

}
