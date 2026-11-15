package org.gks.backend.identity;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * §5 {@code LoginResponse}. {@code mfaRequired=true} iken belirteç alanları ve {@code session} yoktur; yalnızca
 * {@code mfaTicket} döner. Belirteç alanları yalnızca Android'de doludur; web'de belirteçler çerezdedir. Değerler
 * metne dönüşmez.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record LoginResponse(boolean mfaRequired, Boolean mfaEnrollmentRequired, String mfaTicket, boolean mustChangePassword,
		String accessToken, String refreshToken, Instant accessTokenExpiresAt, SessionInfo session) {

	record SessionInfo(UUID sessionId, ClientType clientType, boolean mfaVerified, Instant expiresAt) {
	}

	@Override
	public String toString() {
		return "LoginResponse[mfaRequired=" + mfaRequired + ", session=" + session + "]";
	}

}
