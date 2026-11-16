import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ErrorAlert } from '../../components/ErrorAlert';
import { useServices } from '../../services/registry';
import type { AlertDraftRequest, AlertPreview, AlertPriority, Uuid } from '../../services/types';
import { ParticipantMultiSelect } from '../groups/ParticipantPicker';

export const ALERT_MESSAGE_MAX = 500;
export const ALERT_ACTION_MAX = 200;

export interface AlertDraftInitial {
  message?: string;
  requestedAction?: string;
  correctsAlertId?: Uuid;
}

/**
 * WEB-09 alarm oluşturma: taslak (AL-01) → onay penceresi (alıcı listesi, AC-20.2) → onaylı gönderim (AL-05).
 * Hedef kitle önizlemeden sonra değişirse sunucunun yeni önizlemesi gösterilir ve yeniden onay istenir (AC-20.8).
 * Vazgeçilirse taslak silinir (AL-03).
 */
export function AlertCreateDialog({
  operationId,
  initial = {},
  onClose,
}: {
  operationId: Uuid;
  initial?: AlertDraftInitial;
  onClose: () => void;
}) {
  const services = useServices();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const intent = useIntentKey();
  const [message, setMessage] = useState(initial.message ?? '');
  const [action, setAction] = useState(initial.requestedAction ?? '');
  const [priority, setPriority] = useState<AlertPriority>('CRITICAL');
  const [userIds, setUserIds] = useState<Uuid[]>([]);
  const [groupIds, setGroupIds] = useState<Uuid[]>([]);
  const [touched, setTouched] = useState(false);
  const [preview, setPreview] = useState<AlertPreview | null>(null);
  const [changed, setChanged] = useState(false);

  const groups = useQuery({
    queryKey: [...Q.groups(operationId), { includeArchived: false }],
    queryFn: () => services.groups.list(operationId, {}),
  });
  const groupOptions = groups.data?.items ?? [];

  const draft = useMutation({
    mutationFn: (request: AlertDraftRequest) => services.alerts.create(operationId, request),
    onSuccess: (p) => {
      setPreview(p);
      setChanged(false);
    },
  });
  const send = useMutation({
    mutationFn: ({ p, key }: { p: AlertPreview; key: string }) =>
      services.alerts.send(p.alertId, p.version, p.audienceHash, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (alert) => {
      intent.reset();
      queryClient.setQueryData(Q.alert(alert.id), alert);
      void queryClient.invalidateQueries({ queryKey: Q.alertsRoot() });
      navigate(`/alarmlar/${alert.id}`);
    },
    onError: (error) => {
      // AC-20.8: yeni önizleme; kullanıcı yeniden onaylar (aynı anahtar kullanılabilir, önceki deneme kaydedilmedi).
      if (isApiError(error) && error.code === 'AUDIENCE_CHANGED' && error.extensions.preview) {
        setPreview(error.extensions.preview as AlertPreview);
        setChanged(true);
      }
    },
  });
  const discard = useMutation({ mutationFn: (alertId: Uuid) => services.alerts.deleteDraft(alertId) });

  const errors: Record<string, string> = {};
  if (!message.trim()) errors.message = 'Mesaj zorunludur.';
  if (userIds.length + groupIds.length === 0) errors.targets = 'En az bir kişi veya grup seçin.';
  const shown = touched ? { ...fieldErrorMap(draft.error), ...errors } : fieldErrorMap(draft.error);

  function submitDraft() {
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    draft.mutate({
      message: message.trim(),
      requestedAction: action.trim() || undefined,
      priority,
      targets: { userIds, groupIds },
      correctsAlertId: initial.correctsAlertId,
    });
  }

  function close() {
    if (preview && !send.isSuccess) discard.mutate(preview.alertId);
    onClose();
  }

  const busy = draft.isPending || send.isPending;
  return (
    <Dialog open onClose={busy ? undefined : close} maxWidth="md" fullWidth aria-labelledby="alert-create-title">
      <DialogTitle id="alert-create-title">
        {preview ? 'Alarmı onaylayın' : initial.correctsAlertId ? 'Düzeltilmiş alarm' : 'Acil alarm oluştur'}
      </DialogTitle>
      <DialogContent>
        {!preview ? (
          <Stack spacing={2} mt={1}>
            <Alert severity="warning">
              Mesaja kişisel veri (ad, telefon, sağlık bilgisi) yazmayın; kilit ekranında görünebilir.
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
              helperText={shown.requestedAction ?? 'İsteğe bağlı, ör. "Toplanma alanına geçin"'}
              inputProps={{ maxLength: ALERT_ACTION_MAX }}
            />
            <TextField
              select
              label="Öncelik"
              value={priority}
              onChange={(e) => setPriority(e.target.value as AlertPriority)}
              sx={{ maxWidth: 220 }}
              helperText="İletim her zaman en yüksek öncelikle yapılır."
            >
              <MenuItem value="CRITICAL">Kritik</MenuItem>
              <MenuItem value="HIGH">Yüksek</MenuItem>
            </TextField>
            <Autocomplete
              multiple
              options={groupOptions}
              loading={groups.isPending}
              value={groupOptions.filter((g) => groupIds.includes(g.id))}
              getOptionLabel={(g) => `${g.name} (${g.memberCount} üye)`}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              onChange={(_, v) => setGroupIds(v.map((g) => g.id))}
              noOptionsText="Grup yok"
              renderInput={(params) => (
                <TextField {...params} label="Hedef gruplar" error={!!shown.targets} helperText={shown.targets} />
              )}
            />
            <ParticipantMultiSelect
              operationId={operationId}
              label="Hedef kişiler"
              value={userIds}
              onChange={setUserIds}
              helperText="Yalnızca operasyon katılımcıları. Siz alıcı olmazsınız."
            />
            <ErrorAlert error={draft.error} />
          </Stack>
        ) : (
          <Stack spacing={2} mt={1}>
            {changed && (
              <Alert severity="warning">Önizlemeden sonra alıcı listesi değişti. Yeni listeyi kontrol edip yeniden onaylayın.</Alert>
            )}
            <Typography fontWeight={600}>{preview.message}</Typography>
            {preview.requestedAction && <Typography>İstenen eylem: {preview.requestedAction}</Typography>}
            <Typography>
              <strong>{preview.recipientCount}</strong> kişiye gönderilecek ({preview.operationName}).
            </Typography>
            <List dense aria-label="Alıcılar" sx={{ maxHeight: 260, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
              {preview.recipients.map((r) => (
                <ListItem key={r.id}>
                  <ListItemText primary={r.displayName} secondary={r.groups.map((g) => g.name).join(', ') || 'Kişi olarak seçildi'} />
                </ListItem>
              ))}
            </List>
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
            disabled={busy}
            onClick={() => send.mutate({ p: preview, key: intent.keyFor({ alertId: preview.alertId }) })}
          >
            {send.isPending ? 'Gönderiliyor…' : `${preview.recipientCount} kişiye gönder`}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
