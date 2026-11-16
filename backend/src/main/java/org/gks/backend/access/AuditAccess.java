package org.gks.backend.access;

/** {@code audit.view} (AU-01; K-26): ADM (MFA'lı oturum) veya UYM (yalnızca web); salt-okur. */
public interface AuditAccess {

	Decision canView(Subject subject);

}
