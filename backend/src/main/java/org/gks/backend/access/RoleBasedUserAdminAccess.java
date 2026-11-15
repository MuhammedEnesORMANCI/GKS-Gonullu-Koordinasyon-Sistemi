package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

/** Roller her kararda veritabanından okunur (LD-55); önbellek yok. */
@Component
class RoleBasedUserAdminAccess implements UserAdminAccess {

	private final RoleAssignmentStore store;

	RoleBasedUserAdminAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Directory directory(Subject subject) {
		Grants grants = store.grants(subject.userId());
		Decision admin = admin(subject, grants);
		if (admin.allowed()) {
			return new Directory(Decision.ALLOW, true);
		}
		Decision support = Grants.corporate(subject, grants.has(Role.TEK), Role.TEK);
		if (support.allowed()) {
			return new Directory(Decision.ALLOW, false);
		}
		return new Directory(Decision.anyOf(admin, support), false);
	}

	@Override
	public Decision operationDirectory(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId);
	}

	@Override
	public Decision canDisable(Subject subject, UUID targetUserId) {
		Grants grants = store.grants(subject.userId());
		Decision admin = admin(subject, grants);
		if (admin.allowed()) {
			return admin;
		}
		Decision support = Grants.corporate(subject, grants.has(Role.TEK), Role.TEK);
		if (support.allowed() && store.grants(targetUserId).has(Role.ADM)) {
			return Decision.FORBIDDEN;   // TEK kurum yöneticisinin hesabını kapatamaz
		}
		return Decision.anyOf(admin, support);
	}

	@Override
	public Decision canEnable(Subject subject) {
		return admin(subject, store.grants(subject.userId()));
	}

	@Override
	public Decision canRevokeSessions(Subject subject) {
		Grants grants = store.grants(subject.userId());
		return Decision.anyOf(admin(subject, grants), Grants.corporate(subject, grants.has(Role.TEK), Role.TEK));
	}

	@Override
	public Decision canResetPassword(Subject subject) {
		return admin(subject, store.grants(subject.userId()));
	}

	@Override
	public Decision canResetMfa(Subject subject) {
		return admin(subject, store.grants(subject.userId()));
	}

	private static Decision admin(Subject subject, Grants grants) {
		return Grants.corporate(subject, grants.has(Role.ADM), Role.ADM);
	}

}
