package org.gks.backend.identity;

import java.util.UUID;

import org.gks.backend.SessionPrincipal;

/**
 * Doğrulanmış erişim belirtecinden kurulan kimlik (JWT {@code sub}, {@code sid}, {@code cli}, {@code mfa}).
 * Rol ve izin taşımaz; yetki her istekte access modülünde hesaplanır (§2.2).
 */
public record AuthenticatedSession(UUID userId, UUID sessionId, ClientType client, boolean mfaVerified)
		implements SessionPrincipal {

	@Override
	public boolean webSession() {
		return client == ClientType.WEB;
	}

}
