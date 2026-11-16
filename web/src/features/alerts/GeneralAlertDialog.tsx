import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatLatLon } from '../../components/map/geo';
import { useServices } from '../../services/registry';
import type { AlertPreview, GeneralAlertDraftRequest, Uuid } from '../../services/types';
import { EMPTY_POINT, parsePoint, PointPicker } from '../map/PointPicker';
import { ALERT_ACTION_MAX, ALERT_MESSAGE_MAX } from './AlertCreateDialog';

export const CONFIRM_WORD = 'GÖNDER';

/**
 * ADR-026 Genel Acil Alarm: hedefler birleştirilebilir (kurum geneli yalnızca ADM; KOR yalnızca koordine ettiği
 * operasyonlar, onların grupları ve katılımcıları). Önizleme tekilleştirilmiş alıcı sayısını ve listesini gösterir;
 * gönderim için onay kutusu + "GÖNDER" düğmesi gerekir (sunucu da `confirmed` ister).
 */
export function GeneralAlertDialog({ onClose }: { onClose: () => void }) {
  const services = useServices();
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const intent = useIntentKey();
  const [message, setMessage] = useState('');
  const [action, setAction] = useState('');
  const [organization, setOrganization] = useState(false);
  const [operationIds, setOperationIds] = useState<Uuid[]>([]);
  const [groupIds, setGroupIds] = useState<Uuid[]>([]);
  const [userIds, setUserIds] = useState<Uuid[]>([]);
  const [location, setLocation] = useState(EMPTY_POINT);
  const [touched, setTouched] = useState(false);
  const [preview, setPreview] = useState<AlertPreview | null>(null);
  const [changed, setChanged] = useState(false);
  const [checked, setChecked] = useState(false);

  // Seçilebilir operasyonlar: sunucu kapsamı (ADM tümü, KOR kendi); yalnızca aktifler.
  const ops = useQuery({
    queryKey: Q.operations({ state: ['ACTIVE'] }),
    queryFn: () => services.operationList.list({ state: ['ACTIVE'] }),
  });
  const opList = useMemo(
    () => (ops.data?.items ?? []).filter((o) => perms.isAdm || perms.isKorOf(o.id)),
    [ops.data, perms],
  );
  const groupQueries = useQueries({
    queries: opList.map((o) => ({ queryKey: [...Q.groups(o.id), { includeArchived: false }], queryFn: () => services.groups.list(o.id, {}) })),
  });
  const participantQueries = useQueries({
    queries: opList.map((o) => ({ queryKey: Q.participants(o.id), queryFn: () => services.participants.list(o.id, {}) })),
  });
  const groupOptions = groupQueries.flatMap((q, i) => (q.data?.items ?? []).map((g) => ({ ...g, opName: opList[i]?.name ?? '' })));
  const people = new Map<Uuid, string>();
  participantQueries.forEach((q) => q.data?.items.forEach((p) => people.set(p.user.id, p.user.displayName)));
  const peopleOptions = [...people.entries()].map(([id, displayName]) => ({ id, displayName }));

  const point = parsePoint(location);
  const draft = useMutation({
    mutationFn: (request: GeneralAlertDraftRequest) => services.alerts.createGeneral(request),
    onSuccess: (p) => {
      setPreview(p);
      setChanged(false);
      setChecked(false);
    },
  });
  const send = useMutation({
    mutationFn: ({ p, key }: { p: AlertPreview; key: string }) => services.alerts.send(p.alertId, p.version, p.audienceHash, key, true),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (alert) => {
      intent.reset();
      queryClient.setQueryData(Q.alert(alert.id), alert);
      void queryClient.invalidateQueries({ queryKey: Q.alertsRoot() });
      onClose();
      navigate(`/panel/alarmlar/${alert.id}`);
    },
    onError: (error) => {
      if (isApiError(error) && error.code === 'AUDIENCE_CHANGED' && error.extensions.preview) {
        setPreview(error.extensions.preview as AlertPreview);
        setChanged(true);
        setChecked(false);
      }
    },
  });
  const discard = useMutation({ mutationFn: (alertId: Uuid) => services.alerts.deleteDraft(alertId) });

  const errors: Record<string, string> = {};
  if (!message.trim()) errors.message = 'Mesaj zorunludur.';
  if (!organization && operationIds.length + groupIds.length + userIds.length === 0) errors.targets = 'En az bir hedef seçin.';
  if (point.error) errors.eventLocation = point.error;
  const shown = touched ? { ...fieldErrorMap(draft.error), ...errors } : fieldErrorMap(draft.error);

  function submitDraft() {
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    draft.mutate({
      message: message.trim(),
      requestedAction: action.trim() || undefined,
      targets: { organization, operationIds, groupIds, userIds },
      ...(point.point ? { eventLocation: point.point } : {}),
    });
  }

  function close() {
    if (preview && !send.isSuccess) discard.mutate(preview.alertId);
    onClose();
  }

  const busy = draft.isPending || send.isPending;
  return (
    <Dialog open onClose={busy ? undefined : close} maxWidth="md" fullWidth aria-labelledby="general-alert-title">
      <DialogTitle id="general-alert-title" sx={{ color: 'error.main', fontWeight: 800 }}>
        {preview ? 'Genel acil alarmı onaylayın' : 'Genel Acil Alarm'}
      </DialogTitle>
      <DialogContent>
        {!preview ? (
          <Stack spacing={2} mt={1}>
            <Alert severity="error">
              Genel alarm seçtiğiniz herkesin telefonunda sürekli sesli acil alarm olarak çalar. Yalnızca gerçek acil durumda
              ya da duyurulmuş tatbikatta kullanın. Mesaja kişisel veri yazmayın.
            </Alert>
            <TextField
              label="Mesaj"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              required
              multiline
              minRows={2}
              error={!!shown.message}
              helperText={shown.message ?? `${message.trim().length}/${ALERT_MESSAGE_MAX}`}
              inputProps={{ maxLength: ALERT_MESSAGE_MAX }}
            />
            <TextField
              label="İstenen eylem"
              value={action}
              onChange={(e) => setAction(e.target.value)}
              error={!!shown.requestedAction}
              helperText={shown.requestedAction ?? 'İsteğe bağlı, ör. "Binayı boşaltıp toplanma alanına gidin"'}
              inputProps={{ maxLength: ALERT_ACTION_MAX }}
            />
            <Typography variant="subtitle2">Hedefler (birleştirilebilir)</Typography>
            {perms.isAdm && (
              <FormControlLabel
                control={<Checkbox checked={organization} onChange={(e) => setOrganization(e.target.checked)} />}
                label="Tüm kurum (tüm etkin hesaplar)"
              />
            )}
            <Autocomplete
              multiple
              options={opList}
              loading={ops.isPending}
              value={opList.filter((o) => operationIds.includes(o.id))}
              getOptionLabel={(o) => o.name}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              onChange={(_, v) => setOperationIds(v.map((o) => o.id))}
              noOptionsText="Aktif operasyon yok"
              renderInput={(params) => (
                <TextField {...params} label="Operasyonlar (tüm katılımcılar ve koordinatörler)" error={!!shown.targets} helperText={shown.targets} />
              )}
            />
            <Autocomplete
              multiple
              options={groupOptions}
              value={groupOptions.filter((g) => groupIds.includes(g.id))}
              getOptionLabel={(g) => `${g.name} — ${g.opName}`}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              onChange={(_, v) => setGroupIds(v.map((g) => g.id))}
              noOptionsText="Grup yok"
              renderInput={(params) => <TextField {...params} label="Gruplar" />}
            />
            <Autocomplete
              multiple
              options={peopleOptions}
              value={peopleOptions.filter((p) => userIds.includes(p.id))}
              getOptionLabel={(p) => p.displayName}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              onChange={(_, v) => setUserIds(v.map((p) => p.id))}
              noOptionsText="Kişi yok"
              renderInput={(params) => <TextField {...params} label="Kişiler" helperText="Siz alıcı olmazsınız." />}
            />
            <PointPicker label="Olay yeri" value={location} onChange={setLocation} />
            <ErrorAlert error={draft.error} />
          </Stack>
        ) : (
          <Stack spacing={2} mt={1}>
            {changed && (
              <Alert severity="warning">Önizlemeden sonra alıcı listesi değişti. Yeni listeyi kontrol edip yeniden onaylayın.</Alert>
            )}
            <Typography fontWeight={700}>{preview.message}</Typography>
            {preview.requestedAction && <Typography>İstenen eylem: {preview.requestedAction}</Typography>}
            {preview.eventLocation && <Typography>Olay yeri: {formatLatLon(preview.eventLocation.lat, preview.eventLocation.lon)}</Typography>}
            {preview.targets && (
              <Typography variant="body2" color="text.secondary">
                Hedef: {[
                  preview.targets.organization && 'tüm kurum',
                  preview.targets.operations.length > 0 && `${preview.targets.operations.map((o) => o.displayName).join(', ')}`,
                  preview.targets.groupCount > 0 && `${preview.targets.groupCount} grup`,
                  preview.targets.userCount > 0 && `${preview.targets.userCount} kişi`,
                ].filter(Boolean).join(' · ')}
              </Typography>
            )}
            <Alert severity="error" icon={false}>
              <Typography>
                Tekilleştirilmiş alıcı sayısı: <strong>{preview.recipientCount}</strong>
              </Typography>
            </Alert>
            <List dense aria-label="Alıcılar" sx={{ maxHeight: 240, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
              {preview.recipients.map((r) => (
                <ListItem key={r.id}>
                  <ListItemText primary={r.displayName} secondary={r.groups.map((g) => g.name).join(', ') || undefined} />
                </ListItem>
              ))}
            </List>
            <FormControlLabel
              control={<Checkbox checked={checked} onChange={(e) => setChecked(e.target.checked)} />}
              label={`Mesajı ve ${preview.recipientCount} alıcıyı kontrol ettim; genel acil alarmı göndermek istiyorum.`}
            />
            {!!send.error && !(isApiError(send.error) && send.error.code === 'AUDIENCE_CHANGED') && <ErrorAlert error={send.error} />}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={busy}>
          Vazgeç
        </Button>
        {!preview ? (
          <Button variant="contained" onClick={submitDraft} disabled={busy}>
            {draft.isPending ? 'Hazırlanıyor…' : 'Önizle'}
          </Button>
        ) : (
          <Button
            variant="contained"
            color="error"
            disabled={busy || !checked}
            onClick={() => send.mutate({ p: preview, key: intent.keyFor({ alertId: preview.alertId }) })}
          >
            {send.isPending ? 'Gönderiliyor…' : CONFIRM_WORD}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
