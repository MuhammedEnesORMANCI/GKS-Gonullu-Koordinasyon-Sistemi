import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { isClosedState } from '../../components/format';
import { useServices } from '../../services/registry';

/** Operasyonel ekranların ortak bağlamı: operasyon durumu ve salt-okur kuralı (mapping §0.1). */
export function useOperationScope(opId: string) {
  const services = useServices();
  const perms = usePermissions();
  const operation = useQuery({
    queryKey: Q.operation(opId),
    queryFn: () => services.operations.get(opId),
    enabled: !!opId,
  });
  const closed = isClosedState(operation.data?.state);
  return {
    operation,
    closed,
    /** ADM bu operasyonda yönetim izni taşımıyorsa salt-okur bandı (ADR-015 sonrası normalde görünmez). */
    admReadOnly: perms.isAdmReadOnly(opId),
    can: (permission: string) => !closed && perms.can(permission, opId),
  };
}
