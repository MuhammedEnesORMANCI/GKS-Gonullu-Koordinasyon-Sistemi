import type { UserAdminService } from '../../services/contracts';
import type { UserStatus, UserSummary } from '../../services/types';

/** ID-19: ad sıralaması istemcide, en çok 500 kayıt (backend §5.1; database-physical-design §7.4). */
const PAGE = 100;
const MAX_PAGES = 5;

export async function loadAllUsers(service: UserAdminService, status?: UserStatus) {
  const items: UserSummary[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await service.list({ status, limit: PAGE, cursor });
    items.push(...page.items);
    if (!page.nextCursor) return { items, truncated: false };
    cursor = page.nextCursor;
  }
  return { items, truncated: true };
}
