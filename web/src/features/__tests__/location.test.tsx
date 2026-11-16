import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { liveTopics } from '../../app/live';
import { MOCK_IDS } from '../../services/mock/store';
import type { MeContext } from '../../services/types';
import { renderApp } from '../../test/renderApp';

describe('Son konumlar (WEB-08, K-15, ADR-019)', () => {
  it('KOR tabloyu güncellik etiketleriyle ve paylaşmayanlarla görür; denetim notu sabit', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/konum` });
    const table = await screen.findByRole('table', { name: 'Son konumlar' });
    expect(within(table).getByText('Güncel')).toBeInTheDocument();
    expect(within(table).getByText('Güncel değil (2 dk+)')).toBeInTheDocument();
    expect(within(table).getByText('Son bilinen konum')).toBeInTheDocument();
    expect(within(table).getByText('Cihaz saati farklı')).toBeInTheDocument();
    expect(screen.getByText(/Konum görüntülemeleriniz denetim kaydına yazılır/)).toBeInTheDocument();
    expect(within(screen.getByLabelText('Konum paylaşmayanlar')).getByText(/Paylaşım durduruldu/)).toBeInTheDocument();
  });

  it('ADM konum görmez (yalnızca KOR)', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/konum`, persona: 'ADM' });
    expect(await screen.findByText('Konumları yalnızca operasyonun koordinatörü görebilir.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Son konumlar' })).not.toBeInTheDocument();
  });

  it('konum konusu yalnızca location.view taşıyan operasyonlar için açılır', () => {
    const context = {
      user: { id: 'u1', displayName: 'K' },
      operations: [],
      permissions: { organization: [], operations: { a: ['announcement.send.group', 'location.view'], b: ['announcement.send.group'] }, groups: {} },
    } as unknown as MeContext;
    expect(liveTopics(context)).toEqual(['operation:a', 'operation:b', 'location:a']);
  });
});
