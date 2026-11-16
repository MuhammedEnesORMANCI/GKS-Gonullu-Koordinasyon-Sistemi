import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Grid from '@mui/material/Grid';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { GksMap } from '../../components/map/GksMap';
import { formatLatLon } from '../../components/map/geo';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { OPERATION_STATE_LABEL, formatDateTime } from '../../components/format';
import { StatusChip } from '../../components/panel/StatusChip';
import { useIsMock, useServices } from '../../services/registry';
import type { MapOperationView, OperationState } from '../../services/types';
import { anchor, operationCircles, operationMarkers } from './mapModel';

const stateLabel = (s: OperationState) => OPERATION_STATE_LABEL[s];

function useMapOperations(includePlanned: boolean, enabled: boolean) {
  const services = useServices();
  const states: OperationState[] = includePlanned ? ['ACTIVE', 'PLANNED'] : ['ACTIVE'];
  return useQuery({
    queryKey: Q.mapOperations(states),
    queryFn: () => services.map.operations(states),
    enabled,
    staleTime: 15_000,
  });
}

/** Seçili operasyonun özeti: ad, durum, katılımcı, açık alarm ve yanıt durumu, operasyona bağlantı. */
export function MapOperationSummary({ op }: { op: MapOperationView }) {
  const a = anchor(op);
  return (
    <Paper sx={{ p: 2 }} aria-label={`Seçili operasyon: ${op.name}`} role="region">
      <Stack spacing={1}>
        <Typography variant="h6" component="h2">
          {op.name}
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center">
          <StatusChip kind="operation" value={op.state} />
          <Typography variant="body2">Katılımcı: {op.participantCount}</Typography>
        </Stack>
        {op.openAlert ? (
          <Alert severity="error">
            Açık alarm ({formatDateTime(op.openAlert.sentAt)}): yanıtlayan {op.openAlert.counts.responded}/{op.openAlert.counts.total},
            bekleyen {op.openAlert.counts.pending}, yardım isteyen {op.openAlert.counts.needHelp}
          </Alert>
        ) : (
          <Typography variant="body2" color="text.secondary">
            Açık alarm yok.
          </Typography>
        )}
        <Typography variant="body2">
          {a ? `Konum: ${formatLatLon(a.lat, a.lon)}` : 'Operasyon bölgesi belirtilmemiş.'}
          {op.area?.radiusM ? ` · Yarıçap ${op.area.radiusM} m` : ''}
        </Typography>
        <Stack direction="row" spacing={1}>
          <Button component={RouterLink} to={`/panel/operasyonlar/${op.id}`} variant="contained" size="small">
            Operasyona git
          </Button>
          {op.openAlert && (
            <Button component={RouterLink} to={`/panel/alarmlar/${op.openAlert.id}`} color="error" size="small">
              Alarm takibi
            </Button>
          )}
        </Stack>
      </Stack>
    </Paper>
  );
}

/** WEB "Operasyon Haritası" (`/panel/harita`, ADR-025): ADM tüm, KOR kendi operasyonları; SSE ile tazelenir. */
export function OperationMapPage() {
  const perms = usePermissions();
  const mock = useIsMock('map');
  const allowed = perms.isAdm || perms.isKor;
  const [includePlanned, setIncludePlanned] = useState(false);
  const [selected, setSelected] = useState<string | undefined>();
  const query = useMapOperations(includePlanned, allowed);
  const items = useMemo(() => query.data ?? [], [query.data]);
  const markers = useMemo(() => operationMarkers(items, stateLabel), [items]);
  const circles = useMemo(() => operationCircles(items), [items]);
  const current = items.find((op) => op.id === selected);

  return (
    <>
      <PageHeader title="Operasyon Haritası" mock={mock} />
      {!allowed ? (
        <Alert severity="warning">Harita yalnızca koordinatörler ve kurum yöneticileri içindir.</Alert>
      ) : (
        <Stack spacing={2}>
          <FormControlLabel
            control={<Checkbox checked={includePlanned} onChange={(e) => setIncludePlanned(e.target.checked)} />}
            label="Planlanan operasyonları da göster"
          />
          <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
            {() => (
              <Grid container spacing={2}>
                <Grid item xs={12} md={8}>
                  <Paper sx={{ p: 1 }}>
                    <GksMap
                      markers={markers}
                      circles={circles}
                      height={480}
                      ariaLabel="Operasyon haritası"
                      onMarkerClick={setSelected}
                      selectedId={selected}
                    />
                  </Paper>
                </Grid>
                <Grid item xs={12} md={4}>
                  {current ? (
                    <MapOperationSummary op={current} />
                  ) : (
                    <Typography color="text.secondary">Ayrıntı için haritada ya da listede bir operasyon seçin.</Typography>
                  )}
                </Grid>
                <Grid item xs={12}>
                  {items.length === 0 ? (
                    <Typography color="text.secondary">Gösterilecek operasyon yok.</Typography>
                  ) : (
                    <TableContainer component={Paper}>
                      <Table size="small" aria-label="Haritadaki operasyonlar">
                        <TableHead>
                          <TableRow>
                            <TableCell>Operasyon</TableCell>
                            <TableCell>Durum</TableCell>
                            <TableCell>Katılımcı</TableCell>
                            <TableCell>Alarm</TableCell>
                            <TableCell>Konum</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {items.map((op) => {
                            const a = anchor(op);
                            return (
                              <TableRow key={op.id} selected={op.id === selected}>
                                <TableCell>
                                  <Link component="button" type="button" onClick={() => setSelected(op.id)}>
                                    {op.name}
                                  </Link>
                                </TableCell>
                                <TableCell>
                                  <StatusChip kind="operation" value={op.state} />
                                </TableCell>
                                <TableCell>{op.participantCount}</TableCell>
                                <TableCell>{op.openAlert ? 'Açık alarm' : '—'}</TableCell>
                                <TableCell>{a ? formatLatLon(a.lat, a.lon) : 'Belirtilmemiş'}</TableCell>
                              </TableRow>
                            );
                          })}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </Grid>
              </Grid>
            )}
          </QueryState>
        </Stack>
      )}
    </>
  );
}

/** Özet sayfası için küçük harita kartı (yalnızca ADM/KOR). */
export function OperationMapCard() {
  const perms = usePermissions();
  const allowed = perms.isAdm || perms.isKor;
  const query = useMapOperations(false, allowed);
  const items = useMemo(() => query.data ?? [], [query.data]);
  const markers = useMemo(() => operationMarkers(items, stateLabel), [items]);
  const circles = useMemo(() => operationCircles(items), [items]);
  if (!allowed) return null;
  const alarms = items.filter((op) => op.openAlert).length;
  return (
    <Paper sx={{ p: 2 }} aria-label="Operasyon haritası özeti" role="region">
      <Stack spacing={1}>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Typography variant="h6" component="h2">
            Operasyon Haritası
          </Typography>
          <Button component={RouterLink} to="/panel/harita" size="small">
            Haritayı aç
          </Button>
        </Stack>
        <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
          {() => (
            <>
              <GksMap markers={markers} circles={circles} height={220} ariaLabel="Aktif operasyonlar haritası" />
              <Typography variant="body2">
                Aktif operasyon: {items.length} · Açık alarmlı: {alarms}
              </Typography>
            </>
          )}
        </QueryState>
      </Stack>
    </Paper>
  );
}
