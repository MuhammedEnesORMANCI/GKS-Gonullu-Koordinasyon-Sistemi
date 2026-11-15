import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { useServices } from '../../services/registry';
import type { UserRef, Uuid } from '../../services/types';
import { loadAllUsers } from '../users/loadUsers';

/**
 * Rol atama kişi adayları: kurum kullanıcı dizini (ID-19, ADM'de aktif hesaplar), operasyonun katılımcıları (KOR
 * ataması için) ve rolü olan kişiler. Ada göre sıralı, tekil.
 */
export function useUserCandidates(operationId?: Uuid): { items: UserRef[]; loading: boolean; hint: string } {
  const services = useServices();
  const perms = usePermissions();
  const directoryAllowed = perms.isAdm && perms.can('user.status.view');
  const directory = useQuery({
    queryKey: Q.users({ status: 'ACTIVE' }),
    queryFn: () => loadAllUsers(services.users, 'ACTIVE'),
    enabled: directoryAllowed,
  });
  const holders = useQuery({
    queryKey: Q.roleAssignments({ active: true, limit: 100 }),
    queryFn: () => services.roleAssignments.list({ active: true, limit: 100 }),
  });
  const participants = useQuery({
    queryKey: Q.participants(operationId ?? ''),
    queryFn: () => services.participants.list(operationId!, { limit: 100 }),
    enabled: !!operationId,
  });
  const byId = new Map<Uuid, UserRef>();
  for (const u of directory.data?.items ?? []) byId.set(u.id, { id: u.id, displayName: u.displayName });
  for (const p of participants.data?.items ?? []) byId.set(p.user.id, { id: p.user.id, displayName: p.user.displayName });
  for (const a of holders.data?.items ?? []) byId.set(a.user.id, a.user);
  const items = [...byId.values()].sort((a, b) => a.displayName.localeCompare(b.displayName, 'tr'));
  return {
    items,
    loading: holders.isPending || (directoryAllowed && directory.isPending) || (!!operationId && participants.isPending),
    hint: directoryAllowed
      ? 'Kurumdaki aktif hesaplar listelenir. Yeni kişiyi önce davetle kuruma ekleyin.'
      : 'Operasyon katılımcıları ve rolü olan kişiler listelenir.',
  };
}
