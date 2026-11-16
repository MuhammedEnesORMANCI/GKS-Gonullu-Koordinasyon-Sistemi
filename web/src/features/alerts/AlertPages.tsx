import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { AlertCounts, AlertSummary, AlertView } from '../../services/types';
import { NoParticipantsHint } from '../operations/NoParticipantsHint';
import { useOperationScope } from '../operations/useOperation';
import { AlertCreateDialog, type AlertDraftInitial } from './AlertCreateDialog';
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
                <RouterLink to={`/alarmlar/${a.id}`}>{a.message}</RouterLink>
              </TableCell>
              {showOperation && <TableCell>{a.operationName ?? '—'}</TableCell>}
              <TableCell>
                <Chip size="small" color={ALERT_STATE_COLOR[a.state]} label={ALERT_STATE_LABEL[a.state]} />
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
  const canSend = scope.can('alert.send') && scope.operation.data?.state === 'ACTIVE';
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
          canSend ? (
            <Button variant="contained" color="error" onClick={() => setCreating(true)}>
              Acil alarm gönder
            </Button>
          ) : undefined
        }
      />
      <ClosedOperationBanner show={scope.closed} />
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
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap aria-label="Alarm özeti">
      <Chip label={`Yanıtladı: ${counts.responded}`} color="success" variant="outlined" />
      <Chip label={`Bekliyor: ${counts.pending}`} variant="outlined" />
      <Chip label={`Yanıtsız: ${counts.unanswered}`} color="warning" variant="outlined" />
      <Chip label={`Gönderilemedi: ${counts.failed}`} variant="outlined" />
      <Chip label={`Yardım gerekiyor: ${counts.needHelp}`} color="error" />
    </Stack>
  );
}

/** WEB-10: alarm detayı, alıcı tablosu (AL-09), iptal/kapatma/yeniden gönderim. Canlı: `alert.*` olayları. */
export function AlertDetailPage() {
  const { alertId = '' } = useParams();
  const services = useServices();
  const queryClient = useQueryClient();
  const perms = usePermissions();
  const mock = useIsMock('alerts');
  const intent = useIntentKey();
  const [confirm, setConfirm] = useState<'cancel' | 'close' | 'resend' | null>(null);
  const [reason, setReason] = useState('');
  const [correcting, setCorrecting] = useState<AlertDraftInitial | null>(null);
  const alert = useQuery({ queryKey: Q.alert(alertId), queryFn: () => services.alerts.get(alertId), enabled: !!alertId });
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
  const canManage = !!a && perms.can('alert.send', a.operationId);
  return (
    <>
      <PageHeader title="Acil alarm" mock={mock} />
      <QueryState isPending={alert.isPending} error={alert.error} onRetry={() => void alert.refetch()}>
        {() => (
          <Stack spacing={2}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Chip color={ALERT_STATE_COLOR[a!.state]} label={ALERT_STATE_LABEL[a!.state]} />
              <Typography color="text.secondary">
                <RouterLink to={`/operasyonlar/${a!.operationId}/alarmlar`}>{a!.operationName ?? 'Operasyon'}</RouterLink>
                {a!.sentAt ? ` · ${formatDateTime(a!.sentAt)} · ${a!.sentBy?.displayName ?? ''}` : ''}
              </Typography>
            </Stack>
            <Typography variant="h6" component="p">
              {a!.message}
            </Typography>
            {a!.requestedAction && <Typography>İstenen eylem: {a!.requestedAction}</Typography>}
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
                Bu alarm düzeltildi: <RouterLink to={`/alarmlar/${a!.correctedByAlertId}`}>düzeltilmiş alarm</RouterLink>
              </Alert>
            )}
            <Alert severity="warning" variant="outlined">
              İletim şimdilik uygulama içi bildirim ve canlı akışla yapılıyor; telefon bildirimi (FCM) henüz bağlı değil.
            </Alert>
            {a!.state !== 'DRAFT' && <CountChips counts={a!.counts} />}
            {canManage && (
              <Stack direction="row" spacing={1}>
                {a!.state === 'OPEN' && (
                  <>
                    <Button color="error" variant="outlined" onClick={() => setConfirm('cancel')}>
                      İptal et
                    </Button>
                    <Button variant="outlined" onClick={() => setConfirm('close')}>
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
                {a!.state === 'CANCELLED' && !a!.correctedByAlertId && perms.can('alert.send', a!.operationId) && (
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
      {correcting && a && (
        <AlertCreateDialog operationId={a.operationId} initial={correcting} onClose={() => setCorrecting(null)} />
      )}
    </>
  );
}
