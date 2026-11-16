import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { Freshness } from '../../services/types';
import { useOperationScope } from '../operations/useOperation';
import { LOCATION_MAP_PROVIDER } from './mapProvider';

export const FRESHNESS_LABEL: Record<Freshness, string> = {
  LIVE: 'Güncel',
  STALE: 'Güncel değil (2 dk+)',
  LAST_KNOWN: 'Son bilinen konum',
};
const FRESHNESS_COLOR: Record<Freshness, 'success' | 'warning' | 'default'> = { LIVE: 'success', STALE: 'warning', LAST_KNOWN: 'default' };
const NOT_SHARING_LABEL: Record<string, string> = {
  OFF: 'Paylaşım kapalı',
  STOPPED: 'Paylaşım durduruldu',
  WAITING: 'Paylaşım açık, konum bekleniyor',
};

/**
 * WEB-08 son konumlar (LC-05). Operasyonun KOR'u ve ADM görür (ADR-020); harita sağlayıcısı seçilene kadar tablo.
 * Geçmiş ve iz yoktur (K-16). Canlı: `location:{id}` olaylarıyla en fazla 15 sn'de bir yenilenir.
 */
export function LocationPage() {
  const { opId = '' } = useParams();
  const services = useServices();
  const perms = usePermissions();
  const mock = useIsMock('locations');
  const scope = useOperationScope(opId);
  const [freshness, setFreshness] = useState<Freshness | ''>('');
  const canView = perms.can('location.view', opId);
  const active = scope.operation.data?.state === 'ACTIVE';
  const query = useQuery({
    queryKey: [...Q.locations(opId), { freshness }],
    queryFn: () => services.locations.list(opId, freshness ? { freshness } : {}),
    enabled: !!opId && canView && active,
    staleTime: 15_000,
  });
  const map = LOCATION_MAP_PROVIDER;

  return (
    <>
      <PageHeader title={`Son konumlar${scope.operation.data ? ` — ${scope.operation.data.name}` : ''}`} mock={mock} />
      <Alert severity="info" sx={{ mb: 2 }}>
        Konum görüntülemeleriniz denetim kaydına yazılır. Gönüllüler paylaşımı kendileri başlatır ve istedikleri an durdurur.
      </Alert>
      {!canView ? (
        <Alert severity="warning">Konumları yalnızca operasyonun koordinatörü ve kurum yöneticisi görebilir.</Alert>
      ) : !active && scope.operation.data ? (
        <Alert severity="warning">Operasyon aktif değil; konum gösterilmez.</Alert>
      ) : (
        <Stack spacing={2}>
          <TextField
            select
            label="Güncellik"
            value={freshness}
            onChange={(e) => setFreshness(e.target.value as Freshness | '')}
            sx={{ maxWidth: 260 }}
          >
            <MenuItem value="">Tümü</MenuItem>
            {(Object.keys(FRESHNESS_LABEL) as Freshness[]).map((f) => (
              <MenuItem key={f} value={f}>
                {FRESHNESS_LABEL[f]}
              </MenuItem>
            ))}
          </TextField>
          <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
            {() => (
              <Stack spacing={2}>
                {map && (
                  <Paper sx={{ p: 1 }}>
                    {map.render({ items: query.data!.items, height: 360 })}
                    <Typography variant="caption">{map.attribution}</Typography>
                  </Paper>
                )}
                {query.data!.items.length === 0 ? (
                  <Typography color="text.secondary">Konum paylaşan katılımcı yok.</Typography>
                ) : (
                  <TableContainer component={Paper}>
                    <Table size="small" aria-label="Son konumlar">
                      <TableHead>
                        <TableRow>
                          <TableCell>Ad</TableCell>
                          <TableCell>Grup</TableCell>
                          <TableCell>Güncellik</TableCell>
                          <TableCell>Ölçüm</TableCell>
                          <TableCell>Enlem, boylam</TableCell>
                          <TableCell>Doğruluk</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {query.data!.items.map((l) => (
                          <TableRow key={l.user.id}>
                            <TableCell>{l.user.displayName}</TableCell>
                            <TableCell>{l.groups?.map((g) => g.name).join(', ') || '—'}</TableCell>
                            <TableCell>
                              <Stack direction="row" spacing={0.5}>
                                <Chip size="small" color={FRESHNESS_COLOR[l.freshness]} label={FRESHNESS_LABEL[l.freshness]} />
                                {l.clockSkew && <Chip size="small" variant="outlined" label="Cihaz saati farklı" />}
                                {l.sharingState === 'UNAVAILABLE' && <Chip size="small" variant="outlined" label="Konum alınamıyor" />}
                              </Stack>
                            </TableCell>
                            <TableCell>{formatDateTime(l.measuredAt)}</TableCell>
                            <TableCell>
                              {l.lat.toFixed(5)}, {l.lon.toFixed(5)}
                            </TableCell>
                            <TableCell>{l.accuracy !== undefined ? `±${Math.round(l.accuracy)} m` : '—'}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                )}
                {query.data!.notSharing.length > 0 && (
                  <Paper sx={{ p: 2 }}>
                    <Typography variant="subtitle2" gutterBottom>
                      Konum paylaşmayanlar
                    </Typography>
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap aria-label="Konum paylaşmayanlar">
                      {query.data!.notSharing.map((n) => (
                        <Chip key={n.user.id} label={`${n.user.displayName}: ${NOT_SHARING_LABEL[n.state] ?? n.state}`} />
                      ))}
                    </Stack>
                  </Paper>
                )}
                <Typography variant="caption" color="text.secondary">
                  Oluşturulma: {formatDateTime(query.data!.generatedAt)}
                </Typography>
              </Stack>
            )}
          </QueryState>
        </Stack>
      )}
    </>
  );
}
