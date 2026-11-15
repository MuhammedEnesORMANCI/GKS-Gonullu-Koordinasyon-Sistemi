import { useMemo, useState } from 'react';
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
import { fieldErrorMap } from '../../api/errorMessages';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { UserStatus, Uuid } from '../../services/types';
import { ROLE_LABEL } from '../roles/labels';
import { loadAllUsers } from './loadUsers';

export const USER_STATUS_LABEL: Record<UserStatus, string> = {
  ACTIVE: 'Aktif',
  DISABLED: 'Devre dışı',
  CLOSED: 'Kapatıldı',
};

const STATUS_COLOR: Record<UserStatus, 'success' | 'warning' | 'default'> = {
  ACTIVE: 'success',
  DISABLED: 'warning',
  CLOSED: 'default',
};

type Action = 'disable' | 'enable' | 'revokeSessions' | 'resetPassword' | 'resetMfa';

const ACTION: Record<Action, { label: string; title: string; text: string; done: string; destructive: boolean }> = {
  disable: {
    label: 'Devre dışı bırak',
    title: 'Hesap devre dışı bırakılsın mı?',
    text: 'Kişi giriş yapamaz ve bildirim almaz; tüm oturumları kapatılır, cihazları pasifleşir.',
    done: 'Hesap devre dışı bırakıldı.',
    destructive: true,
  },
  enable: {
    label: 'Etkinleştir',
    title: 'Hesap yeniden etkinleştirilsin mi?',
    text: 'Kişi yeniden giriş yapabilir.',
    done: 'Hesap etkinleştirildi.',
    destructive: false,
  },
  revokeSessions: {
    label: 'Oturumları kapat',
    title: 'Tüm oturumlar kapatılsın mı?',
    text: 'Kişinin tüm cihazlardaki oturumları hemen sonlanır; yeniden giriş yapması gerekir.',
    done: 'Tüm oturumlar kapatıldı.',
    destructive: true,
  },
  resetPassword: {
    label: 'Parolayı sıfırla',
    title: 'Parola sıfırlansın mı?',
    text: 'Kişiye e-postayla geçici parola gönderilir; ilk girişte yeni parola belirlemesi istenir. Tüm oturumları kapatılır.',
    done: 'Parola sıfırlandı; geçici parola e-postayla gönderildi.',
    destructive: true,
  },
  resetMfa: {
    label: "MFA'yı sıfırla",
    title: 'İki adımlı doğrulama sıfırlansın mı?',
    text: 'Kişinin doğrulama uygulaması bağlantısı silinir; sonraki yönetim girişinde yeniden kurar. Tüm oturumları kapatılır.',
    done: 'İki adımlı doğrulama sıfırlandı.',
    destructive: true,
  },
};

/**
 * Kullanıcı ve hesap yönetimi (ID-19 – ID-25). ADM tam izdüşüm ve tüm işlemler; TEK yalnızca ad ve durum görür,
 * hesabı devre dışı bırakıp oturumları kapatabilir (AC-06.5). Yetki ve izdüşüm sunucuda.
 */
export function UsersPage() {
  const services = useServices();
  const perms = usePermissions();
  const mock = useIsMock('users');
  const [params, setParams] = useSearchParams();
  const status = (params.get('durum') ?? '') as UserStatus | '';
  const [search, setSearch] = useState('');
  const [openId, setOpenId] = useState<Uuid | null>(null);

  const canView = perms.can('user.status.view');
  const list = useQuery({
    queryKey: Q.users({ status: status || undefined }),
    queryFn: () => loadAllUsers(services.users, status || undefined),
    enabled: canView,
  });
  const full = perms.isAdm;
  const visible = useMemo(() => {
    const q = search.trim().toLocaleLowerCase('tr');
    return (list.data?.items ?? [])
      .filter(
        (u) =>
          !q ||
          u.displayName.toLocaleLowerCase('tr').includes(q) ||
          (u.fullName ?? '').toLocaleLowerCase('tr').includes(q) ||
          (u.email ?? '').toLocaleLowerCase('tr').includes(q),
      )
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'tr'));
  }, [list.data, search]);

  if (!canView) {
    return (
      <>
        <PageHeader title="Kullanıcılar" />
        <Alert severity="info">Kullanıcı listesi yalnızca kurum yöneticisi ve teknik destek rolüne açıktır.</Alert>
      </>
    );
  }

  return (
    <>
      <PageHeader title="Kullanıcılar" mock={mock} />
      {!full && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Teknik destek görünümü: yalnızca görünen ad ve hesap durumu gösterilir.
        </Alert>
      )}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} mb={2}>
        <TextField
          size="small"
          label="Ara"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={full ? 'Ad, ad soyad veya e-posta' : 'Görünen ad'}
          sx={{ minWidth: 260 }}
        />
        <TextField
          select
          size="small"
          label="Durum"
          value={status}
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.target.value) next.set('durum', e.target.value);
            else next.delete('durum');
            setParams(next, { replace: true });
          }}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">Tümü</MenuItem>
          {(Object.keys(USER_STATUS_LABEL) as UserStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {USER_STATUS_LABEL[s]}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <QueryState isPending={list.isPending} error={list.error} onRetry={() => void list.refetch()}>
        {() =>
          visible.length === 0 ? (
            <Typography color="text.secondary">Bu ölçütlere uyan kullanıcı yok.</Typography>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Kullanıcı listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>Görünen ad</TableCell>
                    {full && <TableCell>Ad soyad</TableCell>}
                    {full && <TableCell>E-posta</TableCell>}
                    <TableCell>Durum</TableCell>
                    <TableCell align="right">İşlem</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {visible.map((u) => (
                    <TableRow key={u.id} hover>
                      <TableCell>{u.displayName}</TableCell>
                      {full && <TableCell>{u.fullName ?? '—'}</TableCell>}
                      {full && <TableCell>{u.email ?? '—'}</TableCell>}
                      <TableCell>
                        {u.status && <Chip size="small" label={USER_STATUS_LABEL[u.status]} color={STATUS_COLOR[u.status]} />}
                      </TableCell>
                      <TableCell align="right">
                        <Button size="small" onClick={() => setOpenId(u.id)} aria-label={`${u.displayName} ayrıntısı`}>
                          Ayrıntı
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {list.data?.truncated && (
        <Typography variant="body2" color="text.secondary" mt={1}>
          İlk 500 kayıt gösteriliyor; durum süzgeciyle daraltın.
        </Typography>
      )}
      {openId && <UserDetailDialog id={openId} onClose={() => setOpenId(null)} />}
    </>
  );
}

function UserDetailDialog({ id, onClose }: { id: Uuid; onClose: () => void }) {
  const services = useServices();
  const session = useSession();
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<Action | null>(null);
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<string | null>(null);
  const detail = useQuery({ queryKey: Q.user(id), queryFn: () => services.users.get(id) });
  const self = session.context?.user.id === id;

  const run = useMutation({
    mutationFn: async (action: Action) => {
      switch (action) {
        case 'disable':
          return services.users.disable(id, reason);
        case 'enable':
          return services.users.enable(id);
        case 'revokeSessions':
          return services.users.revokeSessions(id);
        case 'resetPassword':
          return services.users.resetPassword(id);
        case 'resetMfa':
          return services.users.resetMfa(id);
      }
    },
    onSuccess: (_, action) => {
      setDone(ACTION[action].done);
      setPending(null);
      setReason('');
      return queryClient.invalidateQueries({ queryKey: Q.usersRoot() });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: Q.user(id) }),
  });

  const u = detail.data;
  const actions: Action[] = [];
  if (u && !self) {
    if (perms.can('account.disable') && u.status === 'ACTIVE') actions.push('disable');
    if (perms.isAdm && perms.can('account.disable') && u.status === 'DISABLED') actions.push('enable');
    if (perms.can('session.revoke')) actions.push('revokeSessions');
    if (perms.can('password.reset') && u.status !== 'CLOSED') actions.push('resetPassword');
    if (perms.can('mfa.reset') && u.mfaEnrolled) actions.push('resetMfa');
  }
  const fields = fieldErrorMap(run.error);

  return (
    <>
      <Dialog open onClose={onClose} maxWidth="sm" fullWidth aria-labelledby="user-title">
        <DialogTitle id="user-title">{u?.displayName ?? 'Kullanıcı'}</DialogTitle>
        <DialogContent>
          <QueryState isPending={detail.isPending} error={detail.error} onRetry={() => void detail.refetch()}>
            {() => (
              <Stack spacing={1}>
                {done && (
                  <Alert severity="success" onClose={() => setDone(null)}>
                    {done}
                  </Alert>
                )}
                {!pending && <ErrorAlert error={run.error} context="userAdmin" />}
                <Typography>
                  <strong>Durum:</strong> {u!.status ? USER_STATUS_LABEL[u!.status] : '—'}
                </Typography>
                {u!.fullName && (
                  <Typography>
                    <strong>Ad soyad:</strong> {u!.fullName}
                  </Typography>
                )}
                {u!.email && (
                  <Typography>
                    <strong>E-posta:</strong> {u!.email}
                  </Typography>
                )}
                {u!.phone && (
                  <Typography>
                    <strong>Telefon:</strong> {u!.phone}
                  </Typography>
                )}
                {u!.mfaEnrolled !== undefined && (
                  <Typography>
                    <strong>İki adımlı doğrulama:</strong> {u!.mfaEnrolled ? 'Kurulu' : 'Kurulu değil'}
                  </Typography>
                )}
                {u!.lockedUntil && (
                  <Typography>
                    <strong>Kilitli:</strong> {formatDateTime(u!.lockedUntil)} tarihine kadar
                  </Typography>
                )}
                {u!.roles && (
                  <Typography>
                    <strong>Roller:</strong>{' '}
                    {u!.roles.length === 0
                      ? '—'
                      : u!.roles.map((r) => `${ROLE_LABEL[r.role]} · ${r.scopeName}`).join(', ')}
                  </Typography>
                )}
                {u!.groups && u!.groups.length > 0 && (
                  <Typography>
                    <strong>Gruplar:</strong> {u!.groups.map((g) => g.name).join(', ')}
                  </Typography>
                )}
                {self && (
                  <Typography variant="body2" color="text.secondary">
                    Kendi hesabınızda yönetim işlemi yapılamaz; parolanızı Hesabım sayfasından değiştirin.
                  </Typography>
                )}
              </Stack>
            )}
          </QueryState>
        </DialogContent>
        <DialogActions sx={{ flexWrap: 'wrap', gap: 1 }}>
          {actions.map((a) => (
            <Button key={a} color={ACTION[a].destructive ? 'error' : 'primary'} onClick={() => setPending(a)}>
              {ACTION[a].label}
            </Button>
          ))}
          <Button onClick={onClose}>Kapat</Button>
        </DialogActions>
      </Dialog>

      {pending && (
        <ConfirmDialog
          open
          title={ACTION[pending].title}
          confirmLabel={ACTION[pending].label}
          destructive={ACTION[pending].destructive}
          pending={run.isPending}
          error={<ErrorAlert error={Object.keys(fields).length ? null : run.error} context="userAdmin" />}
          onConfirm={() => run.mutate(pending)}
          onClose={() => {
            setPending(null);
            run.reset();
          }}
        >
          <Typography>{ACTION[pending].text}</Typography>
          {pending === 'disable' && (
            <TextField
              label="Gerekçe (isteğe bağlı)"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              inputProps={{ maxLength: 300 }}
              error={!!fields.reason}
              helperText={fields.reason ?? `${reason.length}/300 · Gerekçe metni denetim kaydına yazılmaz.`}
              multiline
              minRows={2}
            />
          )}
        </ConfirmDialog>
      )}
    </>
  );
}
