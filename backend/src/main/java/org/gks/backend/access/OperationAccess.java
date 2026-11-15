package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

/**
 * Operasyon kaynakları için {@code can()} (ADR-002, authorization.md §2, §5.4). Gerçekleştirme:
 * {@link RoleBasedOperationAccess}.
 */
public interface OperationAccess {

	/** {@code operation.create} (OR-01): yalnızca ADM (yalnızca K1, MFA'lı web). */
	Decision canCreate(Subject subject);

	/**
	 * {@code operation.manage}: ADM kurum kapsamında, KOR yalnızca kendi operasyonunda (yalnızca K1, MFA'lı web).
	 * OR-06 katılımcı listesi de aynı kapsamdır.
	 */
	Decision canManage(Subject subject, UUID operationId);

	/** Operasyonu görme (OR-05): ADM tümü, KOR koordine ettiği (MFA'lı web), GON/LDR katılımcısı olduğu. */
	Decision canView(Subject subject, UUID operationId);

	/** OR-04 kapsamı; filtre sorgu düzeyinde uygulanır (authorization.md §6). */
	ViewScope viewScope(Subject subject);

	/**
	 * @param allOperations ADM (MFA'lı web)
	 * @param operationIds  KOR olarak koordine ettiği (MFA'lı web) ve katılımcısı olduğu operasyonlar
	 */
	record ViewScope(boolean allOperations, Set<UUID> operationIds) {

		public ViewScope {
			operationIds = Set.copyOf(operationIds);
		}

	}

}
