import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
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
import CrisisAlertIcon from '@mui/icons-material/CrisisAlert';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { PALETTE } from '../../app/theme';
import { StatusChip } from '../../components/panel/StatusChip';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { OPERATION_STATE_LABEL, formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { PushStatus } from '../../services/contracts';
import type { AlertCounts, AlertSummary, AlertView } from '../../services/types';
import { NoParticipantsHint } from '../operations/NoParticipantsHint';
import { useOperationScope } from '../operations/useOperation';
import { AlertCreateDialog, type AlertDraftInitial } from './AlertCreateDialog';
import { ChannelLinkButton } from '../messaging/ChannelLinkButton';
import { GksMap } from '../../components/map/GksMap';
import { formatLatLon } from '../../components/map/geo';
import { MAP_COLORS } from '../map/mapModel';
import { ALERT_STATE_COLOR, ALERT_STATE_LABEL, RECIPIENT_STATE_LABEL, RESPONSE_LABEL } from './alertLabels';

export function countsLabel(c: AlertCounts): string {
  return `${c.responded}/${c.total} yanıtladı`;
}

function AlertTable({ items, showOperation }: { items: AlertSummary[]; showOperation: boolean }) {
  return (
    <TableContainer component={Paper}>
      <Table size="small" aria-label="Alarm listesi">
        <TableHead>
          <TableRow>
            <TableCell>Mesaj</TableCell>
            {showOperation && <TableCell>Operasyon</TableCell>}
            <TableCell>Durum</TableCell>
            <TableCell>Yanıt</TableCell>
            <TableCell>Yardım gerekiyor</TableCell>
            <TableCell>Gönderim</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((a) => (
            <TableRow key={a.id} hover>
              <TableCell>
                <RouterLink to={`/panel/alarmlar/${a.id}`}>{a.message}</RouterLink>
              </TableCell>
              {showOperation && <TableCell>{a.scope === 'GENERAL' ? 'Genel Acil Alarm' : a.operationName ?? '—'}</TableCell>}
              <TableCell>
                <StatusChip kind="alert" value={a.state} />
              </TableCell>
              <TableCell>{a.state === 'DRAFT' ? '—' : countsLabel(a.counts)}</TableCell>
              <TableCell>{a.counts.needHelp > 0 ? <Chip size="small" color="error" label={a.counts.needHelp} /> : '0'}</TableCell>
              <TableCell>{a.sentAt ? formatDateTime(a.sentAt) : '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

/** WEB-09: operasyonun alarmları (AL-06) ve yeni alarm (AL-01/05). KOR ve ADM (ADR-018). */
export function AlertListPage() {
  const { opId = '' } = useParams();
  const services = useServices();
  const mock = useIsMock('alerts');
  const scope = useOperationScope(opId);
  const [creating, setCreating] = useState(false);
  const perms = usePermissions();
  const state = scope.operation.data?.state;
  // Hata kaydı (16.11): aktif olmayan operasyonda düğme hiç gösterilmiyordu; kullanıcı neden alarm
  // oluşturamadığını göremiyordu. Artık düğme pasif görünür ve nedeni yazılır (AL-01: yalnızca ACTIVE).
  const hasPermission = perms.can('alert.send', opId);
  const notActive = !!state && state !== 'ACTIVE';
  const canSend = hasPermission && state === 'ACTIVE';
  const query = useQuery({
    queryKey: Q.alerts({ operationId: opId }),
    queryFn: () => services.alerts.list({ operationId: opId }),
    enabled: !!opId,
  });
  return (
    <>
      <PageHeader
        title={`Acil alarmlar${scope.operation.data ? ` — ${scope.operation.data.name}` : ''}`}
        mock={mock}
        actions={
          hasPermission ? (
            <Button variant="contained" color="error" onClick={() => setCreating(true)} disabled={!canSend}>
              Acil alarm gönder
            </Button>
          ) : undefined
        }
      />
      <ClosedOperationBanner show={scope.closed} />
      {hasPermission && notActive && !scope.closed && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Acil alarm yalnızca <strong>Aktif</strong> durumdaki operasyonda gönderilebilir. Bu operasyon şu an{' '}
          <strong>{OPERATION_STATE_LABEL[state!]}</strong>.{' '}
          {perms.can('operation.manage', opId) ? (
            <RouterLink to={`/panel/operasyonlar/${opId}`}>Operasyon sayfasından durumu Aktif yapabilirsiniz.</RouterLink>
          ) : (
            'Operasyonu koordinatör veya kurum yöneticisi aktifleştirebilir.'
          )}
        </Alert>
      )}
      {canSend && <NoParticipantsHint operationId={opId} purpose="Alarmı kişilere veya gruplara göndermek" />}
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Typography color="text.secondary">Bu operasyonda alarm yok.</Typography>
          ) : (
            <AlertTable items={query.data!.items} showOperation={false} />
          )
        }
      </QueryState>
      {creating && <AlertCreateDialog operationId={opId} onClose={() => setCreating(false)} />}
    </>
  );
}

/** Üst menü: açık alarmlar (AL-16; KOR koordine ettiği operasyonlar, ADM kurum). */
export function OpenAlertsPage() {
  const services = useServices();
  const mock = useIsMock('alerts');
  const query = useQuery({ queryKey: Q.alerts({ states: ['OPEN'] }), queryFn: () => services.alerts.list({ states: ['OPEN'] }) });
  return (
    <>
      <PageHeader title="Açık acil alarmlar" mock={mock} />
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Typography color="text.secondary">Açık alarm yok.</Typography>
          ) : (
            <AlertTable items={query.data!.items} showOperation />
          )
        }
      </QueryState>
    </>
  );
}

function CountChips({ counts }: { counts: AlertCounts }) {
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap aria-label="Alarm özeti" sx={{ '& .MuiChip-root': { fontSize: '1rem', height: 34 } }}>
      <Chip label={`Yanıtladı: ${counts.responded}`} color="success" />
      <Chip label={`Bekliyor: ${counts.pending}`} variant="outlined" sx={{ bgcolor: 'background.paper' }} />
      <Chip label={`Yanıtsız: ${counts.unanswered}`} color="warning" />
      <Chip label={`Gönderilemedi: ${counts.failed}`} variant="outlined" sx={{ bgcolor: 'background.paper' }} />
      <Chip label={`Yardım gerekiyor: ${counts.needHelp}`} color="error" />
    </Stack>
  );
}

/**
 * Telefon bildirimi (FCM) durumu (ADR-022): `UP` iken uyarı yok; yüklenirken de gösterilmez. Diğer durumlarda iletimin
 * uygulama içi bildirim ve canlı akışla sürdüğü söylenir.
 */
export function PushStatusNotice({ status }: { status?: PushStatus }) {
  if (!status || status === 'UP') return null;
  const text =
    status === 'DEGRADED'
      ? 'Telefon bildirimi (FCM) şu an çalışmıyor; iletim uygulama içi bildirim ve canlı akışla sürüyor. Sistem yöneticisine bildirin.'
      : status === 'DISABLED'
        ? 'Telefon bildirimi (FCM) kapalı; iletim uygulama içi bildirim ve canlı akışla yapılıyor.'
        : 'Telefon bildirimi (FCM) durumu okunamadı; iletim en azından uygulama içi bildirim ve canlı akışla yapılıyor.';
  return (
    <Alert severity="warning" variant="outlined">
      {text}
    </Alert>
  );
}

/** WEB-10: alarm detayı, alıcı tablosu (AL-09), iptal/kapatma/yeniden gönderim. Canlı: `alert.*` olayları. */
export function AlertDetailPage() {
  const { alertId = '' } = useParams();
  const services = useServices();
  const queryClient = useQueryClient();
  const perms = usePermissions();
  const session = useSession();
  const mock = useIsMock('alerts');
  const intent = useIntentKey();
  const [confirm, setConfirm] = useState<'cancel' | 'close' | 'resend' | null>(null);
  const [reason, setReason] = useState('');
  const [correcting, setCorrecting] = useState<AlertDraftInitial | null>(null);
  const alert = useQuery({ queryKey: Q.alert(alertId), queryFn: () => services.alerts.get(alertId), enabled: !!alertId });
  const push = useQuery({ queryKey: Q.pushStatus(), queryFn: () => services.alerts.pushStatus(), staleTime: 60_000 });
  const recipients = useQuery({
    queryKey: Q.alertRecipients(alertId),
    queryFn: () => services.alerts.recipients(alertId),
    enabled: !!alertId && !!alert.data && alert.data.state !== 'DRAFT',
  });
  const refresh = (view?: AlertView) => {
    if (view) queryClient.setQueryData(Q.alert(alertId), view);
    void queryClient.invalidateQueries({ queryKey: Q.alertsRoot() });
  };
  const action = useMutation({
    mutationFn: async (kind: 'cancel' | 'close' | 'resend') => {
      if (kind === 'cancel') return services.alerts.cancel(alertId, reason.trim() || undefined, intent.keyFor({ kind, alertId }));
      if (kind === 'close') return services.alerts.close(alertId);
      return (await services.alerts.resend(alertId, [], intent.keyFor({ kind, alertId, at: Date.now() }))).alert;
    },
    retry: shouldRetryIdempotentMutation,
    onSuccess: (view) => {
      intent.reset();
      setConfirm(null);
      refresh(view);
    },
  });

  const a = alert.data;
  const me = session.context?.user.id;
  const general = a?.scope === 'GENERAL';
  // ADR-026: genel alarmı ADM ya da gönderen KOR yönetir; operasyon alarmını o operasyonun KOR'u/ADM.
  const canManage = !!a && (general ? perms.isAdm || (perms.isKor && a.createdBy?.id === me) : perms.can('alert.send', a.operationId));
  return (
    <>
      <PageHeader title="Acil alarm" mock={mock} />
      <QueryState isPending={alert.isPending} error={alert.error} onRetry={() => void alert.refetch()}>
        {() => (
          <Stack spacing={2}>
            <Card
              sx={{
                borderLeft: `8px solid ${a!.state === 'OPEN' ? PALETTE.danger : a!.state === 'CLOSED' ? PALETTE.success : PALETTE.border}`,
                bgcolor: a!.state === 'OPEN' ? PALETTE.dangerSoft : 'background.paper',
              }}
              aria-label="Alarm durumu"
            >
              <CardContent sx={{ p: { xs: 2, md: 3 } }}>
                <Stack spacing={1.5}>
                  <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                    <CrisisAlertIcon sx={{ fontSize: 40, color: a!.state === 'OPEN' ? PALETTE.danger : 'text.secondary' }} aria-hidden />
                    <Chip color={ALERT_STATE_COLOR[a!.state]} label={ALERT_STATE_LABEL[a!.state]} sx={{ fontSize: '1rem', height: 34 }} />
                    <Typography color="text.secondary">
                      {general ? (
                        <strong>Genel Acil Alarm</strong>
                      ) : (
                        <RouterLink to={`/panel/operasyonlar/${a!.operationId}/alarmlar`}>{a!.operationName ?? 'Operasyon'}</RouterLink>
                      )}
                      {a!.sentAt ? ` · ${formatDateTime(a!.sentAt)} · ${a!.sentBy?.displayName ?? ''}` : ''}
                    </Typography>
                    {/* Görev 23: ilgili mesaj kanalına hızlı geçiş. */}
                    {a!.operationId && <ChannelLinkButton operationId={a!.operationId} />}
                  </Stack>
                  <Typography variant="h4" component="p" sx={{ fontWeight: 800 }}>
                    {a!.message}
                  </Typography>
                  {a!.requestedAction && <Typography sx={{ fontSize: '1.125rem' }}>İstenen eylem: {a!.requestedAction}</Typography>}
                  {a!.eventLocation && (
                    <Typography>Olay yeri: {formatLatLon(a!.eventLocation.lat, a!.eventLocation.lon)}</Typography>
                  )}
                  {general && a!.targets && (
                    <Typography variant="body2" color="text.secondary">
                      Hedef: {[
                        a!.targets.organization && 'tüm kurum',
                        a!.targets.operations.length > 0 && a!.targets.operations.map((o) => o.displayName).join(', '),
                        a!.targets.groupCount > 0 && `${a!.targets.groupCount} grup`,
                        a!.targets.userCount > 0 && `${a!.targets.userCount} kişi`,
                      ].filter(Boolean).join(' · ')}
                    </Typography>
                  )}
                  {a!.state !== 'DRAFT' && <CountChips counts={a!.counts} />}
                </Stack>
              </CardContent>
            </Card>
            {a!.state === 'CANCELLED' && (
              <Alert severity="info">
                {formatDateTime(a!.cancelledAt)} tarihinde {a!.cancelledBy?.displayName ?? ''} tarafından iptal edildi. Alıcılara
                iptal bildirimi gönderildi.
              </Alert>
            )}
            {a!.state === 'CLOSED' && (
              <Alert severity="success">
                {a!.closeReason === 'OPERATION_CLOSED' ? 'Operasyon kapandığı için kapatıldı.' : `${a!.closedBy?.displayName ?? ''} tarafından kapatıldı.`}
              </Alert>
            )}
            {a!.correctedByAlertId && (
              <Alert severity="info">
                Bu alarm düzeltildi: <RouterLink to={`/panel/alarmlar/${a!.correctedByAlertId}`}>düzeltilmiş alarm</RouterLink>
              </Alert>
            )}
            {a!.eventLocation && (
              <GksMap
                markers={[{ id: 'event', lat: a!.eventLocation.lat, lon: a!.eventLocation.lon, color: MAP_COLORS.ALERT, label: 'Olay yeri' }]}
                height={220}
                ariaLabel="Olay yeri haritası"
              />
            )}
            <PushStatusNotice status={push.data} />
            {canManage && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
                {a!.state === 'OPEN' && (
                  <>
                    <Button color="error" variant="contained" size="large" onClick={() => setConfirm('cancel')}>
                      İptal et
                    </Button>
                    <Button variant="contained" size="large" onClick={() => setConfirm('close')}>
                      Kapat
                    </Button>
                    <Button
                      variant="outlined"
                      disabled={a!.counts.unanswered + a!.counts.failed === 0}
                      onClick={() => setConfirm('resend')}
                    >
                      Yanıtsızlara yeniden gönder
                    </Button>
                  </>
                )}
                {a!.state === 'CANCELLED' && !a!.correctedByAlertId && !general && perms.can('alert.send', a!.operationId) && (
                  <Button
                    variant="contained"
                    color="error"
                    onClick={() =>
                      setCorrecting({ message: a!.message, requestedAction: a!.requestedAction, correctsAlertId: a!.id })
                    }
                  >
                    Düzeltilmiş alarm gönder
                  </Button>
                )}
              </Stack>
            )}
            {a!.state !== 'DRAFT' && (
              <QueryState isPending={recipients.isPending} error={recipients.error} onRetry={() => void recipients.refetch()}>
                {() => (
                  <TableContainer component={Paper}>
                    <Table size="small" aria-label="Alıcılar">
                      <TableHead>
                        <TableRow>
                          <TableCell>Ad</TableCell>
                          <TableCell>Grup</TableCell>
                          <TableCell>Durum</TableCell>
                          <TableCell>Yanıt</TableCell>
                          <TableCell>Gönderildi</TableCell>
                          <TableCell>Teslim edildi</TableCell>
                          <TableCell>Görüldü</TableCell>
                          <TableCell>Yanıt zamanı</TableCell>
                          <TableCell>Hatırlatma</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {recipients.data!.items.map((r) => (
                          <TableRow key={r.id} selected={r.responseOption === 'NEED_HELP'}>
                            <TableCell>{r.user.displayName}</TableCell>
                            <TableCell>{r.group?.name ?? '—'}</TableCell>
                            <TableCell>{RECIPIENT_STATE_LABEL[r.state]}</TableCell>
                            <TableCell>
                              {r.responseOption ? (
                                <Chip
                                  size="small"
                                  color={r.responseOption === 'NEED_HELP' ? 'error' : 'default'}
                                  label={RESPONSE_LABEL[r.responseOption]}
                                />
                              ) : (
                                '—'
                              )}
                            </TableCell>
                            <TableCell>{r.sentAt ? formatDateTime(r.sentAt) : 'bilinmiyor'}</TableCell>
                            <TableCell>{r.deliveredAt ? formatDateTime(r.deliveredAt) : 'bilinmiyor'}</TableCell>
                            <TableCell>{r.seenAt ? formatDateTime(r.seenAt) : 'bilinmiyor'}</TableCell>
                            <TableCell>{r.respondedAt ? formatDateTime(r.respondedAt) : '—'}</TableCell>
                            <TableCell>{r.reminderCount}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                )}
              </QueryState>
            )}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        open={confirm !== null}
        title={confirm === 'cancel' ? 'Alarm iptal edilsin mi?' : confirm === 'close' ? 'Alarm kapatılsın mı?' : 'Yeniden gönderilsin mi?'}
        confirmLabel={confirm === 'cancel' ? 'İptal et' : confirm === 'close' ? 'Kapat' : 'Yeniden gönder'}
        destructive={confirm === 'cancel'}
        pending={action.isPending}
        error={<ErrorAlert error={action.error} />}
        onConfirm={() => confirm && action.mutate(confirm)}
        onClose={() => {
          action.reset();
          setConfirm(null);
        }}
      >
        {confirm === 'cancel' ? (
          <Stack spacing={2}>
            <Typography>Tüm alıcılara (yanıtlamış olanlar dahil) "ALARM İPTAL EDİLDİ" bildirimi gider. Alarm silinmez.</Typography>
            <TextField label="Neden (isteğe bağlı)" value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 500 }} />
          </Stack>
        ) : confirm === 'close' ? (
          <Typography>Alarm kapanır; yeni yanıt kabul edilmez ve hatırlatmalar durur.</Typography>
        ) : (
          <Typography>Yanıtsız ve gönderilemeyen alıcılara alarm yeniden iletilir.</Typography>
        )}
      </ConfirmDialog>
      {correcting && a?.operationId && (
        <AlertCreateDialog operationId={a.operationId} initial={correcting} onClose={() => setCorrecting(null)} />
      )}
    </>
  );
}
