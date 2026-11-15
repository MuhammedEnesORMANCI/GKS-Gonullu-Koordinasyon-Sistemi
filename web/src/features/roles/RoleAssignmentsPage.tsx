import { useMemo, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime, isClosedState } from '../../components/format';
import type { RoleAssignmentFilter } from '../../services/contracts';
import { useIsMock, useServices } from '../../services/registry';
import type { OperationView, Role, RoleAssignmentView, UserRef, Uuid } from '../../services/types';
import { ROLE_LABEL } from './labels';
import { useUserCandidates } from './userCandidates';

/** Web'den atanabilen roller. Katılım (GON) davetle oluşur; burada yalnızca kaldırılabilir. */
const GRANTABLE: Role[] = ['KOR', 'ADM', 'TEK', 'UYM'];
const FILTER_ROLES: Role[] = ['ADM', 'KOR', 'GON', 'TEK', 'UYM'];

/**
 * Rol atama (AC-01 – AC-03): yalnızca ADM (`role.assign`). Koordinatör (KOR) her operasyonda, kurum rolleri
 * (ADM/TEK/UYM) kurum kapsamında atanır; kaldırma onaylıdır. ADM kendi ADM rolünü ve kurumun son ADM rolünü
 * kaldıramaz (sunucu `403`). ADR-015: ADM tüm operasyonları zaten yönetir; KOR ataması operasyon sorumlusunu belirler.
 */
export function RoleAssignmentsPage() {
  const services = useServices();
  const session = useSession();
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const mock = useIsMock('roleAssignments', 'operationList');
  const [params, setParams] = useSearchParams();
  const role = (params.get('rol') ?? '') as Role | '';
  const scopeId = params.get('operasyon') ?? '';
  const onlyActive = params.get('aktif') !== 'hepsi';
  const [granting, setGranting] = useState(false);
  const [revoking, setRevoking] = useState<RoleAssignmentView | null>(null);
  const [granted, setGranted] = useState<RoleAssignmentView | null>(null);

  const canAssign = perms.can('role.assign');
  const organizationId = session.context?.roles.find((r) => r.role === 'ADM' && r.scopeType === 'ORGANIZATION')?.scopeId;

  const operations = useQuery({
    queryKey: Q.operations({}),
    queryFn: () => services.operationList.list({}),
    enabled: canAssign,
    staleTime: 30_000,
  });

  const filter: RoleAssignmentFilter = {
    ...(role ? { role } : {}),
    ...(scopeId ? { scopeId } : {}),
    ...(onlyActive ? { active: true } : {}),
    limit: 100,
  };
  const list = useQuery({
    queryKey: Q.roleAssignments(filter),
    queryFn: () => services.roleAssignments.list(filter),
    enabled: canAssign,
  });

  const revoke = useMutation({
    mutationFn: (id: Uuid) => services.roleAssignments.revoke(id),
    onSuccess: () => {
      setRevoking(null);
      return refreshAfterChange(queryClient);
    },
  });

  const setParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null || value === '') next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };

  if (!canAssign) {
    return (
      <>
        <PageHeader title="Roller" />
        <Alert severity="info">Rol atama yalnızca iki adımlı doğrulamayla giriş yapmış kurum yöneticisine açıktır.</Alert>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Roller"
        mock={mock}
        actions={
          <Button variant="contained" onClick={() => setGranting(true)}>
            Rol ata
          </Button>
        }
      />
      <Typography color="text.secondary" mb={2}>
        Kurum yöneticisi tüm operasyonları KOR ataması olmadan yönetir (ADR-015). Koordinatör ataması, operasyonun
        sorumlularını belirler; gönüllü katılımı davetle oluşur.
      </Typography>

      {granted && (
        <Alert severity="success" onClose={() => setGranted(null)} sx={{ mb: 2 }}>
          {granted.user.displayName} kişisine {ROLE_LABEL[granted.role]} rolü atandı ({granted.scopeName}).
        </Alert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} mb={2} alignItems={{ sm: 'center' }}>
        <TextField select size="small" label="Rol" value={role} onChange={(e) => setParam('rol', e.target.value)} sx={{ minWidth: 220 }}>
          <MenuItem value="">Tümü</MenuItem>
          {FILTER_ROLES.map((r) => (
            <MenuItem key={r} value={r}>
              {ROLE_LABEL[r]}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label="Operasyon"
          value={scopeId}
          onChange={(e) => setParam('operasyon', e.target.value)}
          sx={{ minWidth: 260 }}
        >
          <MenuItem value="">Tümü</MenuItem>
          {(operations.data?.items ?? []).map((o) => (
            <MenuItem key={o.id} value={o.id}>
              {o.name}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel
          control={<Switch checked={onlyActive} onChange={(e) => setParam('aktif', e.target.checked ? null : 'hepsi')} />}
          label="Yalnızca aktif"
        />
      </Stack>

      <QueryState isPending={list.isPending} error={list.error} onRetry={() => void list.refetch()}>
        {() =>
          list.data!.items.length === 0 ? (
            <Typography color="text.secondary">Bu ölçütlere uyan rol ataması yok.</Typography>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Rol atamaları">
                <TableHead>
                  <TableRow>
                    <TableCell>Kişi</TableCell>
                    <TableCell>Rol</TableCell>
                    <TableCell>Kapsam</TableCell>
                    <TableCell>Atayan</TableCell>
                    <TableCell>Atanma</TableCell>
                    <TableCell>Durum</TableCell>
                    <TableCell align="right">İşlem</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {list.data!.items.map((a) => (
                    <TableRow key={a.id} hover>
                      <TableCell>{a.user.displayName}</TableCell>
                      <TableCell>{ROLE_LABEL[a.role]}</TableCell>
                      <TableCell>{a.scopeType === 'ORGANIZATION' ? `Kurum: ${a.scopeName ?? ''}` : a.scopeName}</TableCell>
                      <TableCell>{a.grantedBy?.displayName ?? '—'}</TableCell>
                      <TableCell>{formatDateTime(a.grantedAt)}</TableCell>
                      <TableCell>
                        {a.revokedAt ? (
                          <Chip size="small" label={`Kaldırıldı · ${formatDateTime(a.revokedAt)}`} />
                        ) : (
                          <Chip size="small" color="success" label="Aktif" />
                        )}
                      </TableCell>
                      <TableCell align="right">
                        {!a.revokedAt && (
                          <Button size="small" color="error" onClick={() => setRevoking(a)} aria-label={`${a.user.displayName} ${a.role} rolünü kaldır`}>
                            Kaldır
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {list.data?.nextCursor && (
        <Typography variant="body2" color="text.secondary" mt={1}>
          Daha fazla kayıt var; listeyi rol veya operasyonla daraltın.
        </Typography>
      )}

      <ConfirmDialog
        open={!!revoking}
        title="Rol kaldırılsın mı?"
        confirmLabel="Kaldır"
        destructive
        pending={revoke.isPending}
        error={<ErrorAlert error={revoke.error} context="roleRevoke" />}
        onConfirm={() => revoking && revoke.mutate(revoking.id)}
        onClose={() => {
          setRevoking(null);
          revoke.reset();
        }}
      >
        {revoking && (
          <Typography>
            {revoking.user.displayName} kişisinin {revoking.scopeName} kapsamındaki {ROLE_LABEL[revoking.role]} rolü kaldırılacak.
            {revoking.role === 'GON' && ' Kişi operasyondan ve gruplarından çıkarılır, açık görev atamaları kaldırılır.'}
            {revoking.role === 'KOR' && ' Kişi bu operasyonu artık yönetemez.'}
          </Typography>
        )}
      </ConfirmDialog>

      {granting && (
        <GrantRoleDialog
          organizationId={organizationId}
          operations={(operations.data?.items ?? []).filter((o) => !isClosedState(o.state))}
          defaultOperationId={scopeId || undefined}
          onClose={() => setGranting(false)}
          onGranted={(view) => {
            setGranting(false);
            setGranted(view);
            void refreshAfterChange(queryClient);
          }}
        />
      )}
    </>
  );
}

/** Rol değişikliği menüleri ve operasyon koordinatör listelerini etkiler. */
function refreshAfterChange(queryClient: ReturnType<typeof useQueryClient>) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: Q.roleAssignmentsRoot() }),
    queryClient.invalidateQueries({ queryKey: Q.operationsRoot() }),
    queryClient.invalidateQueries({ queryKey: Q.meContext() }),
  ]);
}

function GrantRoleDialog({
  organizationId,
  operations,
  defaultOperationId,
  onClose,
  onGranted,
}: {
  organizationId?: Uuid;
  operations: OperationView[];
  defaultOperationId?: Uuid;
  onClose: () => void;
  onGranted: (view: RoleAssignmentView) => void;
}) {
  const services = useServices();
  const [role, setRole] = useState<Role>('KOR');
  const [operationId, setOperationId] = useState<Uuid>(defaultOperationId ?? operations[0]?.id ?? '');
  const [user, setUser] = useState<UserRef | null>(null);
  const isOperationRole = role === 'KOR';
  const candidates = useUserCandidates(isOperationRole ? operationId : undefined);
  const grant = useMutation({
    mutationFn: () =>
      services.roleAssignments.grant(
        isOperationRole
          ? { userId: user!.id, role, scopeType: 'OPERATION', scopeId: operationId }
          : { userId: user!.id, role, scopeType: 'ORGANIZATION', scopeId: organizationId! },
      ),
    onSuccess: onGranted,
  });
  const fields = fieldErrorMap(grant.error);
  const missingScope = isOperationRole ? !operationId : !organizationId;
  const options = useMemo(() => candidates.items, [candidates.items]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!user || missingScope || grant.isPending) return;
    grant.mutate();
  };

  return (
    <Dialog open onClose={grant.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="grant-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="grant-title">Rol ata</DialogTitle>
        <DialogContent>
          <Stack spacing={2} mt={1}>
            <ErrorAlert error={Object.keys(fields).length ? null : grant.error} />
            <TextField
              select
              label="Rol"
              value={role}
              onChange={(e) => {
                setRole(e.target.value as Role);
                setUser(null);
                grant.reset();
              }}
              error={!!fields.role}
              helperText={fields.role}
            >
              {GRANTABLE.map((r) => (
                <MenuItem key={r} value={r}>
                  {ROLE_LABEL[r]}
                </MenuItem>
              ))}
            </TextField>
            {isOperationRole && (
              <TextField
                select
                label="Operasyon"
                value={operationId}
                onChange={(e) => {
                  setOperationId(e.target.value);
                  setUser(null);
                  grant.reset();
                }}
                error={!!fields.scopeId || operations.length === 0}
                helperText={fields.scopeId ?? (operations.length === 0 ? 'Kapanmamış operasyon yok.' : undefined)}
              >
                {operations.map((o) => (
                  <MenuItem key={o.id} value={o.id}>
                    {o.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
            {!isOperationRole && !organizationId && (
              <Alert severity="warning">Kurum kapsamı oturum bağlamında bulunamadı; sayfayı yenileyin.</Alert>
            )}
            <Autocomplete
              options={options}
              value={user}
              loading={candidates.loading}
              getOptionLabel={(u) => u.displayName}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              onChange={(_, v) => {
                setUser(v);
                grant.reset();
              }}
              noOptionsText="Uygun kişi yok"
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="Kişi"
                  required
                  error={!!fields.userId}
                  helperText={fields.userId ?? candidates.hint}
                />
              )}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={grant.isPending}>
            Vazgeç
          </Button>
          <Button type="submit" variant="contained" disabled={!user || missingScope || grant.isPending}>
            {grant.isPending ? 'Atanıyor…' : 'Ata'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
