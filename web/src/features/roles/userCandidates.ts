import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { useServices } from '../../services/registry';
import type { UserRef, Uuid } from '../../services/types';

/**
 * Rol atama kişi adayları. Kullanıcı dizini ucu (ID-19) yokken kaynaklar: operasyonun katılımcıları (KOR ataması için)
 * ve rol ataması olan tüm kişiler (aktif). Ada göre sıralı, tekil.
 */
export function useUserCandidates(operationId?: Uuid): { items: UserRef[]; loading: boolean; hint: string } {
  const services = useServices();
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
  for (const p of participants.data?.items ?? []) byId.set(p.user.id, { id: p.user.id, displayName: p.user.displayName });
  for (const a of holders.data?.items ?? []) byId.set(a.user.id, a.user);
  const items = [...byId.values()].sort((a, b) => a.displayName.localeCompare(b.displayName, 'tr'));
  return {
    items,
    loading: holders.isPending || (!!operationId && participants.isPending),
    hint: operationId
      ? 'Operasyon katılımcıları ve rolü olan kişiler listelenir.'
      : 'Rolü olan kişiler listelenir. Yeni kişiyi önce davetle kuruma ekleyin.',
  };
}
