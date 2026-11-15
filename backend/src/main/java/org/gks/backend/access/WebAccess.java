package org.gks.backend.access;

import java.util.UUID;

/**
 * Web oturumu açılabilir mi (backend-api-design.md §2.3, LD-03, K-19)? Kural access modülündedir:
 * web oturumu ADM, KOR, TEK veya UYM rolü ister; ADM veya KOR rolü ayrıca TOTP doğrulaması ister.
 * <p>
 * Gerçekleştirme: {@link RoleBasedWebAccess} (aktif rol atamalarından).
 */
public interface WebAccess {

	WebLogin webLogin(UUID userId);

	/**
	 * Android girişi (ADR-014): ADM veya KOR rolü varsa oturum yalnızca TOTP doğrulamasından sonra (yönetim oturumu,
	 * 12 sa mutlak) açılır; aksi hâlde MFA'sız gönüllü oturumu. Android girişi hiçbir rolde reddedilmez.
	 */
	WebLogin mobileLogin(UUID userId);

	enum WebLogin {
		/** Web rolü yok (yalnızca GON/LDR): {@code 403 CLIENT_NOT_ALLOWED}. */
		NOT_ALLOWED,
		/** Yalnızca TEK/UYM: MFA'sız web oturumu. */
		ALLOWED,
		/** ADM veya KOR: oturum yalnızca TOTP doğrulamasından sonra açılır. */
		MFA_REQUIRED
	}

}
