package org.gks.backend.identity;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Güvenlik bağlamındaki kimlik. {@link #getName()} kullanıcı kimliğidir (JWT {@code sub}). */
final class SessionAuthentication extends AbstractAuthenticationToken {

	private final AuthenticatedSession session;

	SessionAuthentication(AuthenticatedSession session) {
		super(List.of());
		this.session = session;
		setAuthenticated(true);
	}

	@Override
	public AuthenticatedSession getPrincipal() {
		return session;
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return session.userId().toString();
	}

}
