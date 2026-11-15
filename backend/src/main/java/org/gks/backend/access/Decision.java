package org.gks.backend.access;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;

/**
 * {@code can()} sonucu (authorization.md §2; backend-api-design.md §2.4, §3.3). {@link #CLIENT_NOT_ALLOWED}: izin
 * öznenin bir rolünden gelirdi ama oturum türü (web/Android) veya MFA koşulu sağlanmıyor (ADR-013, K-19, LD-01).
 */
public enum Decision {

	ALLOW, FORBIDDEN, CLIENT_NOT_ALLOWED;

	public boolean allowed() {
		return this == ALLOW;
	}

	/** @throws ApiException {@code 403 FORBIDDEN} veya {@code 403 CLIENT_NOT_ALLOWED} */
	public void require() {
		switch (this) {
			case ALLOW -> {
			}
			case FORBIDDEN -> throw ApiException.of(Code.FORBIDDEN);
			case CLIENT_NOT_ALLOWED -> throw ApiException.of(Code.CLIENT_NOT_ALLOWED);
		}
	}

	/** Alternatif yollardan biri izin veriyorsa izin; yoksa istemci kapısı reddi yetki reddinden önce söylenir. */
	static Decision anyOf(Decision... decisions) {
		Decision result = FORBIDDEN;
		for (Decision decision : decisions) {
			if (decision == ALLOW) {
				return ALLOW;
			}
			if (decision == CLIENT_NOT_ALLOWED) {
				result = CLIENT_NOT_ALLOWED;
			}
		}
		return result;
	}

}
