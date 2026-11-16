import { useState } from 'react';
import Alert from '@mui/material/Alert';
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
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import type { AuditFilter } from '../../services/contracts';
import { useIsMock, useServices } from '../../services/registry';
import type { ActorBasis } from '../../services/types';

/** Eylem adları (backend-api-design.md §12.5); bilinmeyen eylem olduğu gibi gösterilir. */
export const ACTION_LABEL: Record<string, string> = {
  ROLE_ASSIGNED: 'Rol atandı',
  ROLE_REVOKED: 'Rol kaldırıldı',
  ACCOUNT_DISABLED: 'Hesap devre dışı bırakıldı',
  ACCOUNT_ENABLED: 'Hesap etkinleştirildi',
  SESSIONS_REVOKED: 'Oturumlar kapatıldı',
  PASSWORD_RESET_BY_ADMIN: 'Parola sıfırlandı',
  MFA_RESET: 'İki adımlı doğrulama sıfırlandı',
  INVITATION_CREATED: 'Davet gönderildi',
  INVITATION_REVOKED: 'Davet iptal edildi',
  PUBLIC_ANNOUNCEMENT_CREATED: 'Kamu duyurusu oluşturuldu',
  PUBLIC_ANNOUNCEMENT_UPDATED: 'Kamu duyurusu düzenlendi',
  PUBLIC_ANNOUNCEMENT_PUBLISHED: 'Kamu duyurusu yayımlandı',
  PUBLIC_ANNOUNCEMENT_UNPUBLISHED: 'Kamu duyurusu yayından kaldırıldı',
  OPERATION_CREATED: 'Operasyon oluşturuldu',
  OPERATION_UPDATED: 'Operasyon düzenlendi',
  OPERATION_STATE_CHANGED: 'Operasyon durumu değişti',
  GROUP_CREATED: 'Grup oluşturuldu',
  GROUP_UPDATED: 'Grup düzenlendi',
  GROUP_MEMBER_ADDED: 'Gruba üye eklendi',
  GROUP_MEMBER_REMOVED: 'Gruptan üye çıkarıldı',
  GROUP_LEADER_CHANGED: 'Grup lideri değişti',
  MEETING_POINT_UPDATED: 'Toplanma noktası güncellendi',
  TASK_CREATED: 'Görev oluşturuldu',
  TASK_UPDATED: 'Görev düzenlendi',
  TASK_PUBLISHED: 'Görev yayımlandı',
  TASK_STATE_CHANGED: 'Görev durumu değişti',
  ASSIGNMENT_ADDED: 'Görev ataması eklendi',
  ASSIGNMENT_REMOVED: 'Görev ataması kaldırıldı',
  ANNOUNCEMENT_SENT: 'Duyuru gönderildi',
  ALERT_DRAFTED: 'Alarm taslağı oluşturuldu',
  ALERT_DRAFT_DELETED: 'Alarm taslağı silindi',
  ALERT_SENT: 'Acil alarm gönderildi',
  ALERT_CORRECTED: 'Alarm düzeltildi',
  ALERT_RESPONDED: 'Alarma yanıt verildi',
  ALERT_RESENT: 'Alarm yeniden gönderildi',
  ALERT_CANCELLED: 'Alarm iptal edildi',
  ALERT_CLOSED: 'Alarm kapatıldı',
  LOCATION_SHARING_STARTED: 'Konum paylaşımı başlatıldı',
  LOCATION_SHARING_STOPPED: 'Konum paylaşımı durduruldu',
  LOCATION_VIEWED: 'Konumlar görüntülendi',
  LOGIN_SUCCEEDED: 'Giriş yapıldı',
  LOGIN_FAILED: 'Başarısız giriş',
  LOGOUT: 'Çıkış yapıldı',
  LOGOUT_ALL: 'Tüm oturumlardan çıkış',
  MFA_ENROLLED: 'İki adımlı doğrulama kuruldu',
  REFRESH_TOKEN_REUSED: 'Oturum belirteci tekrar kullanıldı (oturum kapatıldı)',
};

export const BASIS_LABEL: Record<ActorBasis, string> = {
  KOR: 'Koordinatör ataması',
  ADM: 'Kurum yöneticisi yetkisi',
  TEK: 'Teknik destek',
  UYM: 'Uyum',
  LEADER: 'Grup lideri',
  SELF: 'Kendisi',
  SYSTEM: 'Sistem',
};

const CLIENT_LABEL = { WEB: 'Web', ANDROID: 'Android', SYSTEM: 'Sistem' } as const;

/**
 * Denetim kayıtları (WEB-11, AU-01): salt-okur, zaman azalan. ADM ve UYM görür (K-26). ADR-015: ADM'nin KOR ataması
 * olmadan yaptığı operasyon işlemleri "Kurum yöneticisi yetkisi" dayanağıyla ayırt edilir. Düzenleme veya silme yoktur.
 */
export function AuditPage() {
  const services = useServices();
  const perms = usePermissions();
  const mock = useIsMock('audit');
  const [action, setAction] = useState('');
  const [basis, setBasis] = useState<ActorBasis | ''>('');
  const [operationId, setOperationId] = useState('');
  const canView = perms.can('audit.view');

  const operations = useQuery({
    queryKey: Q.operations({}),
    queryFn: () => services.operationList.list({}),
    enabled: canView && perms.isAdm,
    staleTime: 30_000,
  });
  const filter: AuditFilter = {
    ...(action ? { action } : {}),
    ...(basis ? { actorBasis: basis } : {}),
    ...(operationId ? { operationId } : {}),
  };
  const entries = useInfiniteQuery({
    queryKey: Q.audit(filter),
    queryFn: ({ pageParam }) => services.audit.list({ ...filter, limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    enabled: canView,
  });

  if (!canView) {
    return (
      <>
        <PageHeader title="Denetim kayıtları" />
        <Alert severity="info">Denetim kayıtları yalnızca kurum yöneticisi ve uyum rolüne açıktır.</Alert>
      </>
    );
  }
  const items = entries.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <>
      <PageHeader title="Denetim kayıtları" mock={mock} />
      <Typography color="text.secondary" mb={2}>
        Kayıtlar yalnızca okunabilir; değiştirilemez ve silinemez. Mesaj içeriği, parola ve konum bilgisi kayda yazılmaz.
      </Typography>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} mb={2}>
        <TextField select size="small" label="Eylem" value={action} onChange={(e) => setAction(e.target.value)} sx={{ minWidth: 260 }}>
          <MenuItem value="">Tümü</MenuItem>
          {Object.entries(ACTION_LABEL).map(([key, label]) => (
            <MenuItem key={key} value={key}>
              {label}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label="Yetki dayanağı"
          value={basis}
          onChange={(e) => setBasis(e.target.value as ActorBasis | '')}
          sx={{ minWidth: 240 }}
        >
          <MenuItem value="">Tümü</MenuItem>
          {(Object.keys(BASIS_LABEL) as ActorBasis[]).map((b) => (
            <MenuItem key={b} value={b}>
              {BASIS_LABEL[b]}
            </MenuItem>
          ))}
        </TextField>
        {perms.isAdm && (
          <TextField
            select
            size="small"
            label="Operasyon"
            value={operationId}
            onChange={(e) => setOperationId(e.target.value)}
            sx={{ minWidth: 260 }}
          >
            <MenuItem value="">Tümü</MenuItem>
            {(operations.data?.items ?? []).map((o) => (
              <MenuItem key={o.id} value={o.id}>
                {o.name}
              </MenuItem>
            ))}
          </TextField>
        )}
      </Stack>
      <QueryState isPending={entries.isPending} error={entries.error} onRetry={() => void entries.refetch()}>
        {() =>
          items.length === 0 ? (
            <Typography color="text.secondary">Bu ölçütlere uyan kayıt yok.</Typography>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Denetim kayıtları">
                <TableHead>
                  <TableRow>
                    <TableCell>Zaman</TableCell>
                    <TableCell>Kişi</TableCell>
                    <TableCell>Yetki dayanağı</TableCell>
                    <TableCell>Eylem</TableCell>
                    <TableCell>Operasyon</TableCell>
                    <TableCell>İstemci</TableCell>
                    <TableCell>IP</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((e) => (
                    <TableRow key={e.id}>
                      <TableCell>{formatDateTime(e.at)}</TableCell>
                      <TableCell>{e.actor?.displayName ?? (e.action === 'LOGIN_FAILED' ? 'Tanınmayan hesap' : 'Sistem')}</TableCell>
                      <TableCell>
                        <Chip size="small" label={BASIS_LABEL[e.actorBasis] ?? e.actorBasis} color={e.actorBasis === 'ADM' ? 'warning' : 'default'} />
                      </TableCell>
                      <TableCell>{ACTION_LABEL[e.action] ?? e.action}</TableCell>
                      <TableCell>{e.operationName ?? '—'}</TableCell>
                      <TableCell>{CLIENT_LABEL[e.client] ?? e.client}</TableCell>
                      <TableCell>{e.ip ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {entries.hasNextPage && (
        <Button sx={{ mt: 1 }} onClick={() => void entries.fetchNextPage()} disabled={entries.isFetchingNextPage}>
          {entries.isFetchingNextPage ? 'Yükleniyor…' : 'Daha eski kayıtlar'}
        </Button>
      )}
    </>
  );
}
