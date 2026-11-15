package org.gks.backend.identity;

/** §5 {@code LoginRequest}. Parola metne dönüşmez. */
record LoginRequest(String email, String password, ClientType clientType, String installationId) {

	@Override
	public String toString() {
		return "LoginRequest[clientType=" + clientType + "]";
	}

}
