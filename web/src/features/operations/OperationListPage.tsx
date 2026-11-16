import { useState } from 'react';
import Button from '@mui/material/Button';
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
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { OperationCreateDialog } from './OperationCreateDialog';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { OPERATION_STATE_COLOR, OPERATION_STATE_LABEL, formatDateTime, isClosedState } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { OperationState } from '../../services/types';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATES: OperationState[] = ['PLANNED', 'ACTIVE', 'SUSPENDED', 'COMPLETED', 'CANCELLED'];

/** WEB-03 liste (OR-04). */
export function OperationListPage() {
  const services = useServices();
  const navigate = useNavigate();
  const listMock = useIsMock('operationList');
  const detailMock = useIsMock('operations');
  const [state, setState] = useState<OperationState | ''>('');
  const [openId, setOpenId] = useState('');
  const [creating, setCreating] = useState(false);
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const canCreate = perms.can('operation.create');
  const filter = state ? { state: [state] } : {};

  const query = useQuery({
    queryKey: Q.operations(filter),
    queryFn: () => services.operationList.list(filter),
  });

  const now = Date.now();

  return (
    <>
      <PageHeader
        title="Operasyonlar"
        mock={listMock}
        actions={
          canCreate ? (
            <Button variant="contained" onClick={() => setCreating(true)}>
              Operasyon oluştur
            </Button>
          ) : undefined
        }
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} mb={2}>
        <TextField
          select
          size="small"
          label="Durum"
          value={state}
          onChange={(e) => setState(e.target.value as OperationState | '')}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">Tümü</MenuItem>
          {STATES.map((s) => (
            <MenuItem key={s} value={s}>
              {OPERATION_STATE_LABEL[s]}
            </MenuItem>
          ))}
        </TextField>
        {listMock && !detailMock && (
          <Stack
            direction="row"
            spacing={1}
            component="form"
            onSubmit={(e) => {
              e.preventDefault();
              if (UUID_RE.test(openId.trim())) navigate(`/panel/operasyonlar/${openId.trim()}`);
            }}
          >
            <TextField
              size="small"
              label="Gerçek operasyonu kimlikle aç"
              helperText="Liste ucu (GET /operations) olmadığından geliştirme kolaylığı"
              value={openId}
              onChange={(e) => setOpenId(e.target.value)}
              error={openId !== '' && !UUID_RE.test(openId.trim())}
              sx={{ minWidth: 340 }}
            />
            <Button type="submit" variant="outlined" disabled={!UUID_RE.test(openId.trim())}>
              Aç
            </Button>
          </Stack>
        )}
      </Stack>
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Stack spacing={1} alignItems="flex-start" py={2}>
              <Typography color="text.secondary">
                {state
                  ? 'Bu durumda operasyon yok.'
                  : canCreate
                    ? 'Henüz operasyon yok. İlk operasyonu oluşturun; gruplar, görevler ve operasyon davetleri operasyona bağlıdır.'
                    : 'Size atanmış operasyon yok. Kurum yöneticisi sizi bir operasyona koordinatör olarak atadığında burada görünür.'}
              </Typography>
              {canCreate && !state && (
                <Button variant="outlined" onClick={() => setCreating(true)}>
                  Operasyon oluştur
                </Button>
              )}
            </Stack>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Operasyon listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>Ad</TableCell>
                    <TableCell>Durum</TableCell>
                    <TableCell>Koordinatörler</TableCell>
                    <TableCell>Planlanan bitiş</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {query.data!.items.map((op) => {
                    const overdue =
                      !isClosedState(op.state) && !!op.plannedEndAt && new Date(op.plannedEndAt).getTime() < now;
                    return (
                      <TableRow key={op.id} hover>
                        <TableCell>
                          <RouterLink to={`/panel/operasyonlar/${op.id}`}>{op.name}</RouterLink>
                        </TableCell>
                        <TableCell>
                          <Chip size="small" label={OPERATION_STATE_LABEL[op.state]} color={OPERATION_STATE_COLOR[op.state]} />
                        </TableCell>
                        <TableCell>{op.coordinators.map((c) => c.displayName).join(', ') || '—'}</TableCell>
                        <TableCell>
                          {formatDateTime(op.plannedEndAt) || '—'}
                          {overdue && <Chip size="small" color="warning" label="Bitiş geçti" sx={{ ml: 1 }} />}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {creating && (
        <OperationCreateDialog
          onClose={() => setCreating(false)}
          onCreated={(op) => {
            setCreating(false);
            void queryClient.invalidateQueries({ queryKey: Q.operationsRoot() });
            // Yeni KOR ataması izinleri değiştirir (grup/görev/davet yönetimi açılır).
            void queryClient.invalidateQueries({ queryKey: Q.meContext() });
            navigate(`/panel/operasyonlar/${op.id}`);
          }}
        />
      )}
      {query.data?.nextCursor && (
        <Typography variant="body2" color="text.secondary" mt={1}>
          Daha fazla kayıt var (sayfalama bu sürümde ilk sayfayla sınırlı).
        </Typography>
      )}
    </>
  );
}
