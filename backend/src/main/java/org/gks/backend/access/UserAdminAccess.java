package org.gks.backend.access;

import java.util.UUID;

/**
 * Kullanıcı ve hesap yönetimi izinleri (ID-19 – ID-25; authorization.md §5.4). Kurumsal izinlerdir: istemci ve MFA
 * kapısı {@link Grants#corporate} (ADM MFA'lı oturum; TEK yalnızca web, MFA'sız). Gerçekleştirme:
 * {@link RoleBasedUserAdminAccess}.
 */
public interface UserAdminAccess {

	/**
	 * {@code user.status.view} kurum geneli liste ve ayrıntı (ID-19/ID-20). ADM tam izdüşüm; TEK yalnızca
	 * {@code id}, {@code displayName}, {@code status} (AC-06.5, TD-14).
	 */
	Directory directory(Subject subject);

	/** Operasyonun katılımcıları: KOR kendi operasyonu, ADM tümü ({@link Grants#coordinates}, ADR-015). */
	Decision operationDirectory(Subject subject, UUID operationId);

	/** {@code account.disable} (ID-21): ADM; TEK yalnızca ADM rolü olmayan hesapta (son yöneticiyi kilitlememek için). */
	Decision canDisable(Subject subject, UUID targetUserId);

	/** ID-22 geri alma: yalnızca ADM. */
	Decision canEnable(Subject subject);

	/** {@code session.revoke} (ID-23): ADM veya TEK. */
	Decision canRevokeSessions(Subject subject);

	/** {@code password.reset} (ID-24): yalnızca ADM. */
	Decision canResetPassword(Subject subject);

	/** {@code mfa.reset} (ID-25): yalnızca ADM. */
	Decision canResetMfa(Subject subject);

	/** @param full {@code true}: ADM izdüşümü (iletişim bilgisi, roller); {@code false}: TEK durum izdüşümü */
	record Directory(Decision decision, boolean full) {

		public static final Directory NONE = new Directory(Decision.FORBIDDEN, false);

		public boolean allowed() {
			return decision.allowed();
		}

	}

}
