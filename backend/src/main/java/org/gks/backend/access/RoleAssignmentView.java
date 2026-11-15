package org.gks.backend.access;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** backend-api-design.md §6 {@code RoleAssignmentView}. İlk ADM'de {@code grantedBy} yoktur (DB §4.1). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleAssignmentView(UUID id, UserRef user, Role role, ScopeType scopeType, UUID scopeId, String scopeName,
		UserRef grantedBy, Instant grantedAt, Instant revokedAt) {

	public record UserRef(UUID id, String displayName) {
	}

}
