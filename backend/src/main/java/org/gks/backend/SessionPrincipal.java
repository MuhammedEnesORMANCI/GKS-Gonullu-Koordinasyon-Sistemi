package org.gks.backend;

import java.util.UUID;

/**
 * Doğrulanmış oturumun modüllerden bağımsız görünümü (JWT {@code sub}, {@code cli}, {@code mfa}). identity'nin kimlik
 * nesnesi bunu gerçekleştirir; diğer modüller identity tipine bağımlı olmadan özneyi kurar (ADR-001, döngü yok).
 */
public interface SessionPrincipal {

	UUID userId();

	UUID sessionId();

	boolean webSession();

	boolean mfaVerified();

}
