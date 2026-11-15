import { useMemo, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime, isClosedState } from '../../components/format';
import type { InvitationFilter } from '../../services/contracts';
import { useIsMock, useServices } from '../../services/registry';
import type {
  InvitationCreateRequest,
  InvitationKind,
  InvitationStatus,
  InvitationView,
  OperationView,
  ScopeType,
  Uuid,
} from '../../services/types';

const STATUS_LABEL: Record<InvitationStatus, string> = {
  PENDING: 'Bekliyor',
  ACCEPTED: 'Kabul edildi',
  DECLINED: 'Reddedildi',
  EXPIRED: 'Süresi doldu',
  REVOKED: 'İptal edildi',
};

const STATUS_COLOR: Record<InvitationStatus, 'default' | 'success' | 'warning' | 'info' | 'error'> = {
  PENDING: 'info',
  ACCEPTED: 'success',
  DECLINED: 'default',
  EXPIRED: 'warning',
  REVOKED: 'default',
};

const KIND_LABEL: Record<InvitationKind, string> = {
  NEW_USER: 'Yeni kullanıcı',
  OPERATION: 'Kayıtlı kullanıcı',
};

const STATUSES: InvitationStatus[] = ['PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'REVOKED'];

/** Kapsam seçimi: `ORG` kurum daveti (yalnızca ADM), aksi hâlde operasyon kimliği. */
const ORG = 'ORG';
const ALL = '';

export const EMAIL_MAX = 254;

/**
 * Davet yönetimi (WEB-03 operasyon daveti, WEB-04 kurum daveti): ID-13 liste, ID-12 gönder (`Idempotency-Key`),
 * ID-14 iptal (onaylı). Kod yalnızca e-postada bulunur; hiçbir yanıtta ve ekranda görünmez (LD-37).
 * Yetki: kurum kapsamı ADM; operasyon kapsamı ADM veya o operasyonun KOR'u. Sunucu her istekte yeniden denetler.
 */
export function InvitationsPage() {
  const services = useServices();
  const session = useSession();
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const mock = useIsMock('invitations', 'operationList');
  const [params, setParams] = useSearchParams();
  const scope = params.get('kapsam') ?? ALL;
  const statusParam = params.get('durum');
  const status: InvitationStatus | '' = statusParam === null ? 'PENDING' : (statusParam as InvitationStatus | '');
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<InvitationView | null>(null);
  const [created, setCreated] = useState<InvitationView | null>(null);

  const organizationId = session.context?.roles.find((r) => r.role === 'ADM' && r.scopeType === 'ORGANIZATION')?.scopeId;
  const canInviteOrg = !!organizationId && perms.can('invitation.create');

  const operations = useQuery({
    queryKey: Q.operations({}),
    queryFn: () => services.operationList.list({}),
    staleTime: 30_000,
  });
  const invitableOps = useMemo(
    () => (operations.data?.items ?? []).filter((o) => perms.can('invitation.create', o.id)),
    [operations.data, perms],
  );

  const filter: InvitationFilter = {
    ...(scope === ORG ? { scopeType: 'ORGANIZATION' as const } : {}),
    ...(scope && scope !== ORG ? { scopeType: 'OPERATION' as const, scopeId: scope } : {}),
    ...(status ? { status: [status] } : {}),
  };

  const list = useQuery({
    queryKey: Q.invitations(filter),
    queryFn: () => services.invitations.list(filter),
  });

  const revoke = useMutation({
    mutationFn: (id: Uuid) => services.invitations.revoke(id),
    onSuccess: () => {
      setRevoking(null);
      return queryClient.invalidateQueries({ queryKey: Q.invitationsRoot() });
    },
    onError: (e) => {
      // Davet artık bekleme durumunda değil: güncel liste yüklenir.
      if (isApiError(e) && e.code === 'INVALID_STATE_TRANSITION') {
        void queryClient.invalidateQueries({ queryKey: Q.invitationsRoot() });
      }
    },
  });

  const setParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };

  const canInviteAny = canInviteOrg || invitableOps.some((o) => !isClosedState(o.state));
  const scopeLabel = (i: InvitationView) => (i.scopeType === 'ORGANIZATION' ? `Kurum: ${i.scopeName ?? ''}` : i.scopeName ?? i.scopeId);

  return (
    <>
      <PageHeader
        title="Davetler"
        mock={mock}
        actions={
          canInviteAny ? (
            <Button variant="contained" onClick={() => setCreating(true)}>
              Davet gönder
            </Button>
          ) : undefined
        }
      />

      {created && (
        <Alert severity="success" onClose={() => setCreated(null)} sx={{ mb: 2 }}>
          {created.email} adresine davet gönderildi.{' '}
          {created.kind === 'NEW_USER'
            ? 'Kişi, e-postadaki kodla Android uygulamasında hesabını oluşturacak.'
            : 'Kişi kayıtlı; daveti Android uygulamasında kabul edip operasyona katılacak.'}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} mb={2}>
        <TextField
          select
          size="small"
          label="Kapsam"
          value={scope}
          onChange={(e) => setParam('kapsam', e.target.value || null)}
          sx={{ minWidth: 260 }}
        >
          <MenuItem value={ALL}>Tümü</MenuItem>
          {canInviteOrg && <MenuItem value={ORG}>Kurum</MenuItem>}
          {invitableOps.map((o) => (
            <MenuItem key={o.id} value={o.id}>
              {o.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label="Durum"
          value={status}
          onChange={(e) => setParam('durum', e.target.value)}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">Tümü</MenuItem>
          {STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {STATUS_LABEL[s]}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <QueryState isPending={list.isPending} error={list.error} onRetry={() => void list.refetch()}>
        {() =>
          list.data!.items.length === 0 ? (
            <Stack spacing={1} alignItems="flex-start">
              <Typography color="text.secondary">
                {scope || status !== 'PENDING'
                  ? 'Bu ölçütlere uyan davet yok.'
                  : canInviteAny
                    ? 'Bekleyen davet yok. Gönüllüleri e-postayla davet edin; kayıtlı değillerse e-postadaki kodla hesap açarlar.'
                    : 'Bekleyen davet yok.'}
              </Typography>
              {canInviteAny && (
                <Button variant="outlined" onClick={() => setCreating(true)}>
                  Davet gönder
                </Button>
              )}
            </Stack>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Davet listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>E-posta</TableCell>
                    <TableCell>Kapsam</TableCell>
                    <TableCell>Tür</TableCell>
                    <TableCell>Durum</TableCell>
                    <TableCell>Son geçerlilik</TableCell>
                    <TableCell>Gönderen</TableCell>
                    <TableCell align="right">İşlem</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {list.data!.items.map((i) => (
                    <TableRow key={i.id} hover>
                      <TableCell>{i.email ?? '—'}</TableCell>
                      <TableCell>{scopeLabel(i)}</TableCell>
                      <TableCell>{KIND_LABEL[i.kind]}</TableCell>
                      <TableCell>
                        <Chip size="small" label={STATUS_LABEL[i.status]} color={STATUS_COLOR[i.status]} />
                      </TableCell>
                      <TableCell>{formatDateTime(i.expiresAt)}</TableCell>
                      <TableCell>{i.createdBy?.displayName ?? '—'}</TableCell>
                      <TableCell align="right">
                        {i.status === 'PENDING' && (
                          <Button size="small" color="error" onClick={() => setRevoking(i)}>
                            İptal et
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
          Daha fazla kayıt var (sayfalama bu sürümde ilk sayfayla sınırlı).
        </Typography>
      )}

      <ReceivedInvitations items={session.context?.pendingInvitations ?? []} />

      {creating && (
        <InvitationCreateDialog
          organizationId={canInviteOrg ? organizationId : undefined}
          operations={invitableOps.filter((o) => !isClosedState(o.state))}
          defaultScope={scope}
          onClose={() => setCreating(false)}
          onCreated={(invitation) => {
            setCreating(false);
            setCreated(invitation);
            void queryClient.invalidateQueries({ queryKey: Q.invitationsRoot() });
          }}
        />
      )}

      <ConfirmDialog
        open={!!revoking}
        title="Daveti iptal et"
        confirmLabel="İptal et"
        destructive
        pending={revoke.isPending}
        error={
          revoke.error ? (
            <ErrorAlert error={revoke.error} context="invitation" />
          ) : undefined
        }
        onConfirm={() => revoking && revoke.mutate(revoking.id)}
        onClose={() => {
          setRevoking(null);
          revoke.reset();
        }}
      >
        <Typography>
          {revoking?.email} için gönderilen davet iptal edilecek. Davet kodu ve bağlantısı geçersiz olur.
        </Typography>
      </ConfirmDialog>
    </>
  );
}

function InvitationCreateDialog({
  organizationId,
  operations,
  defaultScope,
  onClose,
  onCreated,
}: {
  organizationId: Uuid | undefined;
  operations: OperationView[];
  defaultScope: string;
  onClose: () => void;
  onCreated: (invitation: InvitationView) => void;
}) {
  const services = useServices();
  const intent = useIntentKey();
  const initialScope =
    defaultScope === ORG && organizationId
      ? ORG
      : operations.some((o) => o.id === defaultScope)
        ? defaultScope
        : (operations[0]?.id ?? (organizationId ? ORG : ''));
  const [email, setEmail] = useState('');
  const [scope, setScope] = useState(initialScope);
  const [fields, setFields] = useState<Record<string, string>>({});

  const create = useMutation({
    mutationFn: (request: InvitationCreateRequest) => services.invitations.create(request, intent.keyFor(request)),
    onSuccess: (invitation) => {
      intent.reset();
      onCreated(invitation);
    },
    onError: (e) => setFields(fieldErrorMap(e)),
  });

  const trimmed = email.trim();
  const emailInvalid = trimmed !== '' && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(trimmed);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (create.isPending || !trimmed || emailInvalid || !scope) return;
    const scopeType: ScopeType = scope === ORG ? 'ORGANIZATION' : 'OPERATION';
    const scopeId = scope === ORG ? organizationId! : scope;
    setFields({});
    create.mutate({ email: trimmed, scopeType, scopeId });
  }

  const errorText =
    create.error && !(isApiError(create.error) && create.error.code === 'VALIDATION_FAILED' && Object.keys(fields).length > 0)
      ? messageFor(create.error)
      : null;

  return (
    <Dialog open onClose={create.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="invite-title">
      <form onSubmit={handleSubmit} noValidate>
        <DialogTitle id="invite-title">Davet gönder</DialogTitle>
        <DialogContent>
          <Stack spacing={2} mt={1}>
            {errorText && (
              <Alert severity="error" role="alert">
                {errorText}
              </Alert>
            )}
            <TextField
              label="E-posta"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={emailInvalid || !!fields.email}
              helperText={fields.email ?? (emailInvalid ? 'Geçerli bir e-posta adresi girin.' : ' ')}
              inputProps={{ maxLength: EMAIL_MAX }}
              required
              autoFocus
            />
            <TextField
              select
              label="Kapsam"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              error={!!fields.scopeId || !!fields.scopeType}
              helperText={
                fields.scopeId ??
                fields.scopeType ??
                (scope === ORG
                  ? 'Kurum daveti hesap açar, rol vermez (LD-23). Kayıtlı e-postaya gönderilemez.'
                  : 'Kayıtlı kişi daveti uygulamada kabul eder; kayıtlı değilse e-postadaki kodla hesap açar.')
              }
              required
            >
              {organizationId && <MenuItem value={ORG}>Kurum</MenuItem>}
              {operations.map((o) => (
                <MenuItem key={o.id} value={o.id}>
                  {o.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={create.isPending}>
            Vazgeç
          </Button>
          <Button type="submit" variant="contained" disabled={create.isPending || !trimmed || emailInvalid || !scope}>
            {create.isPending ? 'Gönderiliyor…' : 'Gönder'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** Oturumdaki kullanıcıya gelen bekleyen operasyon davetleri (`MeContext.pendingInvitations`); yanıt Android'de (ID-17/18). */
function ReceivedInvitations({ items }: { items: InvitationView[] }) {
  return (
    <Paper sx={{ p: 2, mt: 3 }}>
      <Typography variant="h6" component="h2" gutterBottom>
        Bana gelen davetler
      </Typography>
      {items.length === 0 ? (
        <Typography color="text.secondary">Size gelen bekleyen davet yok.</Typography>
      ) : (
        <Stack spacing={1}>
          {items.map((i) => (
            <Typography key={i.id}>
              <strong>{i.scopeName ?? i.scopeId}</strong> — {i.createdBy?.displayName ?? '—'} davet etti · son geçerlilik{' '}
              {formatDateTime(i.expiresAt)}
            </Typography>
          ))}
          <Typography variant="body2" color="text.secondary">
            Operasyon davetleri mobil uygulamadan kabul edilir veya reddedilir.
          </Typography>
        </Stack>
      )}
    </Paper>
  );
}
