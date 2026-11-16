import { describe, expect, it, vi } from 'vitest';
import { Q } from '../../app/queryKeys';
import { loadParticipantCandidates } from '../groups/ParticipantPicker';
import type { ParticipantView } from '../../services/types';

function participant(i: number, status: 'ACTIVE' | 'DISABLED' = 'ACTIVE'): ParticipantView {
  return {
    user: { id: `u-${i}`, displayName: `Gönüllü ${i}`, status },
    joinedAt: '2026-11-16T20:00:00Z',
    groups: [],
    openAssignmentCount: 0,
  } as unknown as ParticipantView;
}

describe('Grup/görev kişi seçicisi adayları (RAPOR_7 madde 1)', () => {
  it('tüm sayfaları okur (100+ katılımcı), devre dışı hesapları çıkarır', async () => {
    const all = Array.from({ length: 230 }, (_, i) => participant(i, i === 5 || i === 150 ? 'DISABLED' : 'ACTIVE'));
    const list = vi.fn(async (_op: string, p: { limit?: number; cursor?: string; notInGroup?: string }) => {
      const start = p.cursor ? Number(p.cursor) : 0;
      const end = start + (p.limit ?? 50);
      return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : undefined };
    });
    const page = await loadParticipantCandidates({ list }, 'op-1', 'g-1');
    expect(list).toHaveBeenCalledTimes(3);
    expect(list.mock.calls[0]?.[1]).toMatchObject({ limit: 100, notInGroup: 'g-1' });
    expect(page.items).toHaveLength(228);
    expect(page.items.some((p) => p.user.id === 'u-5' || p.user.id === 'u-150')).toBe(false);
    expect(page.items.some((p) => p.user.id === 'u-229')).toBe(true);
    expect(page.nextCursor).toBeUndefined();
  });

  it('aday sorgu anahtarı katılımcı listesinin anahtarından ayrı ama onun önekinde (geçersizleştirme sürer)', () => {
    const list = Q.participants('op-1');
    const candidates = Q.participantCandidates('op-1');
    expect(candidates).not.toEqual(list);
    expect(candidates.slice(0, list.length)).toEqual(list);
  });
});
