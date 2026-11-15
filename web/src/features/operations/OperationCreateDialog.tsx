import { useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation } from '@tanstack/react-query';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { useSession } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { fromLocalInput } from '../../components/format';
import { useServices } from '../../services/registry';
import type { OperationCreateRequest, OperationView } from '../../services/types';
import { DESCRIPTION_MAX, NAME_MAX, NAME_MIN } from './OperationDetailPage';

/**
 * WEB-03 operasyon oluşturma (OR-01, yalnızca ADM). Kullanıcı listesi ucu (ID-19) henüz olmadığından koordinatör
 * oturumdaki yöneticinin kendisidir (K-07: ADM kendini KOR atayabilir; atama denetime yazılır).
 */
export function OperationCreateDialog({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (op: OperationView) => void;
}) {
  const services = useServices();
  const session = useSession();
  const intent = useIntentKey();
  const me = session.context?.user;
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [plannedEnd, setPlannedEnd] = useState('');
  const [touched, setTouched] = useState(false);
  const [serverFields, setServerFields] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: (request: OperationCreateRequest) => services.operations.create(request, intent.keyFor(request)),
    onSuccess: (op) => {
      intent.reset();
      onCreated(op);
    },
    onError: (e) => setServerFields(fieldErrorMap(e)),
  });

  const trimmed = name.trim();
  const plannedEndAt = fromLocalInput(plannedEnd);
  const errors: Record<string, string> = {};
  if (!trimmed) errors.name = 'Bu alan zorunludur.';
  else if (trimmed.length < NAME_MIN) errors.name = `En az ${NAME_MIN} karakter.`;
  else if (trimmed.length > NAME_MAX) errors.name = `En fazla ${NAME_MAX} karakter.`;
  if (description.length > DESCRIPTION_MAX) errors.description = `En fazla ${DESCRIPTION_MAX} karakter.`;
  if (plannedEndAt && new Date(plannedEndAt).getTime() <= Date.now()) errors.plannedEndAt = 'Gelecekte bir zaman seçin.';
  const shown = { ...(touched ? errors : {}), ...serverFields };

  function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    if (mutation.isPending || Object.keys(errors).length > 0 || !me) return;
    setServerFields({});
    mutation.mutate({
      name: trimmed,
      ...(description.trim() ? { description: description.trim() } : {}),
      ...(plannedEndAt ? { plannedEndAt } : {}),
      coordinatorIds: [me.id],
    });
  }

  const errorText =
    mutation.error && !(isApiError(mutation.error) && mutation.error.code === 'VALIDATION_FAILED' && Object.keys(serverFields).length > 0)
      ? messageFor(mutation.error)
      : null;

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="op-create-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="op-create-title">Operasyon oluştur</DialogTitle>
        <DialogContent>
          <Stack spacing={2} mt={1}>
            {errorText && (
              <Alert severity="error" role="alert">
                {errorText}
              </Alert>
            )}
            <TextField
              label="Ad"
              value={name}
              onChange={(e) => setName(e.target.value)}
              error={!!shown.name}
              helperText={shown.name ?? `${NAME_MIN}–${NAME_MAX} karakter`}
              inputProps={{ maxLength: NAME_MAX }}
              required
              autoFocus
            />
            <TextField
              label="Açıklama"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              error={!!shown.description}
              helperText={shown.description ?? ' '}
              inputProps={{ maxLength: DESCRIPTION_MAX }}
              multiline
              minRows={2}
            />
            <TextField
              label="Planlanan bitiş"
              type="datetime-local"
              value={plannedEnd}
              onChange={(e) => setPlannedEnd(e.target.value)}
              error={!!shown.plannedEndAt}
              helperText={shown.plannedEndAt ?? 'İsteğe bağlı'}
              InputLabelProps={{ shrink: true }}
            />
            <Stack spacing={0.5}>
              <Typography variant="subtitle2">Koordinatör</Typography>
              <Typography>{me?.displayName ?? '—'} (siz)</Typography>
              {shown.coordinatorIds && (
                <Typography color="error" variant="body2">
                  {shown.coordinatorIds}
                </Typography>
              )}
              <Typography variant="body2" color="text.secondary">
                Operasyonu siz koordine edersiniz; grup ve görev yönetimi bu sayede açılır. Atama denetim kaydına yazılır.
              </Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">
              Operasyon "Planlandı" durumunda açılır; hazır olduğunuzda detay sayfasından aktif edebilirsiniz.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={mutation.isPending}>
            Vazgeç
          </Button>
          <Button type="submit" variant="contained" disabled={mutation.isPending || !me}>
            {mutation.isPending ? 'Oluşturuluyor…' : 'Oluştur'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
