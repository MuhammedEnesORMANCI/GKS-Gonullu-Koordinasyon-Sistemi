import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import {
  OPERATION_STATE_COLOR,
  OPERATION_STATE_LABEL,
  formatDateTime,
  fromLocalInput,
  isClosedState,
  toLocalInput,
} from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import { OperationParticipants } from './OperationParticipants';
import type { OperationState, OperationUpdateRequest, OperationView } from '../../services/types';

/** Çekirdek geçişler (backend §7.2). `SUSPENDED` (askıya alma) Genişletilmiş halkadır; buradan başlatılmaz. */
const ACTIONS: Record<OperationState, OperationState[]> = {
  PLANNED: ['ACTIVE', 'CANCELLED'],
  ACTIVE: ['COMPLETED', 'CANCELLED'],
  SUSPENDED: ['ACTIVE', 'COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: [],
};

const ACTION_LABEL: Partial<Record<OperationState, string>> = {
  ACTIVE: 'Aktif et',
  COMPLETED: 'Kapat (Tamamlandı)',
  CANCELLED: 'Kapat (İptal)',
};

export const NAME_MIN = 3;
export const NAME_MAX = 120;
export const DESCRIPTION_MAX = 2000;

/** WEB-03 operasyon detayı: OR-05 (detay), OR-02 (düzenle), OR-03 (durum). */
export function OperationDetailPage() {
  const { opId = '' } = useParams();
  const services = useServices();
  const queryClient = useQueryClient();
  const perms = usePermissions();
  const mock = useIsMock('operations');
  const [target, setTarget] = useState<OperationState | null>(null);
  const [editing, setEditing] = useState(false);

  const query = useQuery({
    queryKey: Q.operation(opId),
    queryFn: () => services.operations.get(opId),
    enabled: !!opId,
  });

  const invalidateAll = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: Q.operationsRoot() }),
      queryClient.invalidateQueries({ queryKey: Q.meContext() }),
    ]);

  const stateMutation = useMutation({
    mutationFn: async (targetState: OperationState): Promise<OperationView> => {
      try {
        return await services.operations.changeState(opId, { targetState });
      } catch (e) {
        // §6.3: hedef duruma zaten ulaşıldıysa başarı say, hata gösterme.
        if (isApiError(e) && e.code === 'INVALID_STATE_TRANSITION') {
          const fresh = await services.operations.get(opId).catch(() => undefined);
          if (fresh) queryClient.setQueryData(Q.operation(opId), fresh);
          if (fresh?.state === targetState) return fresh;
        }
        throw e;
      }
    },
    onSuccess: (op) => {
      queryClient.setQueryData(Q.operation(opId), op);
      setTarget(null);
      void invalidateAll();
    },
    onError: (e) => {
      if (isApiError(e) && (e.code === 'OPERATION_CLOSED' || e.code === 'NOT_FOUND')) void invalidateAll();
    },
  });

  const op = query.data;
  const canManage = perms.can('operation.manage', opId);
  const closed = isClosedState(op?.state);
  const overdue =
    !!op && !closed && (op.overdue || (!!op.plannedEndAt && new Date(op.plannedEndAt).getTime() < Date.now()));

  return (
    <>
      <PageHeader title={op?.name ?? 'Operasyon'} mock={mock} />
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() => (
          <Stack spacing={2}>
            <ClosedOperationBanner show={closed} />
            {overdue && (
              <Alert
                severity="warning"
                action={
                  canManage ? (
                    <Button color="inherit" size="small" onClick={() => setEditing(true)}>
                      Bitişi güncelle
                    </Button>
                  ) : undefined
                }
              >
                Planlanan bitiş geçti; operasyon henüz kapatılmadı.
              </Alert>
            )}
            <Paper sx={{ p: 2 }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography variant="subtitle2">Durum:</Typography>
                  <Chip label={OPERATION_STATE_LABEL[op!.state]} color={OPERATION_STATE_COLOR[op!.state]} size="small" />
                </Stack>
                <Typography>
                  <strong>Açıklama:</strong> {op!.description || '—'}
                </Typography>
                <Typography>
                  <strong>Planlanan bitiş:</strong> {formatDateTime(op!.plannedEndAt) || '—'}
                </Typography>
                <Typography>
                  <strong>Koordinatörler:</strong> {op!.coordinators.map((c) => c.displayName).join(', ') || '—'}
                  {perms.can('role.assign') && !closed && (
                    <Button size="small" component={RouterLink} to={`/roller?rol=KOR&operasyon=${opId}`} sx={{ ml: 1 }}>
                      Koordinatörleri yönet
                    </Button>
                  )}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Oluşturma: {formatDateTime(op!.createdAt)}
                  {op!.activatedAt && ` · Aktif edilme: ${formatDateTime(op!.activatedAt)}`}
                  {op!.closedAt && ` · Kapanış: ${formatDateTime(op!.closedAt)}`}
                </Typography>
              </Stack>
            </Paper>

            {canManage && !closed && (
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Button variant="outlined" onClick={() => setEditing(true)}>
                  Düzenle
                </Button>
                {ACTIONS[op!.state].map((s) => (
                  <Button
                    key={s}
                    variant={s === 'ACTIVE' ? 'contained' : 'outlined'}
                    color={s === 'ACTIVE' ? 'primary' : 'error'}
                    onClick={() => {
                      stateMutation.reset();
                      setTarget(s);
                    }}
                  >
                    {ACTION_LABEL[s]}
                  </Button>
                ))}
              </Stack>
            )}

            <Stack direction="row" spacing={1}>
              <Button component={RouterLink} to={`/operasyonlar/${opId}/gruplar`}>
                Gruplar
              </Button>
              <Button component={RouterLink} to={`/operasyonlar/${opId}/gorevler`}>
                Görevler
              </Button>
              {perms.can('alert.view', opId) && (
                <Button component={RouterLink} to={`/operasyonlar/${opId}/alarmlar`} color="error">
                  Acil alarmlar
                </Button>
              )}
            </Stack>

            {(canManage || perms.isAdm) && (
              <OperationParticipants operationId={opId} canInvite={!closed && perms.can('invitation.create', opId)} />
            )}

            <ConfirmDialog
              open={target !== null}
              title={target === 'ACTIVE' ? 'Operasyonu aktif et' : 'Operasyonu kapat'}
              confirmLabel={target ? (ACTION_LABEL[target] ?? 'Onayla') : 'Onayla'}
              destructive={target !== 'ACTIVE'}
              pending={stateMutation.isPending}
              error={
                stateMutation.error ? (
                  <Alert severity="error" role="alert">
                    {messageFor(stateMutation.error, 'operationState')}
                  </Alert>
                ) : undefined
              }
              onClose={() => setTarget(null)}
              onConfirm={() => target && stateMutation.mutate(target)}
            >
              {target === 'ACTIVE' ? (
                <Typography>Operasyon aktif edildiğinde konum paylaşımı ve acil alarm kullanılabilir olur.</Typography>
              ) : (
                <>
                  <Typography>
                    Operasyonu kapatırsanız: tüm konum paylaşımları durur, açık alarmlar kapanır, gruplar arşivlenir,
                    operasyon kapsamlı roller pasifleşir. Bu işlem geri alınamaz.
                  </Typography>
                  {op!.openAlertCount !== undefined && <Typography>Açık alarm: {op!.openAlertCount}</Typography>}
                  {op!.activeSharingCount !== undefined && (
                    <Typography>Konum paylaşan kişi: {op!.activeSharingCount}</Typography>
                  )}
                </>
              )}
            </ConfirmDialog>

            {editing && (
              <OperationEditDialog
                operation={op!}
                onClose={() => setEditing(false)}
                onSaved={(saved) => {
                  queryClient.setQueryData(Q.operation(opId), saved);
                  setEditing(false);
                  void invalidateAll();
                }}
              />
            )}
          </Stack>
        )}
      </QueryState>
    </>
  );
}

function OperationEditDialog({
  operation,
  onClose,
  onSaved,
}: {
  operation: OperationView;
  onClose: () => void;
  onSaved: (op: OperationView) => void;
}) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [name, setName] = useState(operation.name);
  const [description, setDescription] = useState(operation.description ?? '');
  const [plannedEnd, setPlannedEnd] = useState(toLocalInput(operation.plannedEndAt));

  const mutation = useMutation({
    mutationFn: (request: OperationUpdateRequest) => services.operations.update(operation.id, request),
    onSuccess: onSaved,
    onError: (e) => {
      if (isApiError(e) && e.code === 'OPERATION_CLOSED') {
        void queryClient.invalidateQueries({ queryKey: Q.operation(operation.id) });
      }
    },
  });

  const trimmedName = name.trim();
  const plannedIso = fromLocalInput(plannedEnd);
  const localErrors: Record<string, string> = {};
  if (trimmedName.length < NAME_MIN || trimmedName.length > NAME_MAX) {
    localErrors.name = `Ad ${NAME_MIN}–${NAME_MAX} karakter olmalı.`;
  }
  if (description.trim().length > DESCRIPTION_MAX) localErrors.description = `En fazla ${DESCRIPTION_MAX} karakter.`;
  const originalPlanned = fromLocalInput(toLocalInput(operation.plannedEndAt));
  if (plannedIso && plannedIso !== originalPlanned && new Date(plannedIso).getTime() <= Date.now()) {
    localErrors.plannedEndAt = 'Planlanan bitiş gelecekte olmalı.';
  }
  const serverErrors = fieldErrorMap(mutation.error);

  // OR-02 kısmi güncelleme (LD-52): yalnızca değişen alanlar; boşaltılan isteğe bağlı alan `null` ile temizlenir.
  const request: OperationUpdateRequest = {};
  if (trimmedName !== operation.name) request.name = trimmedName;
  if (description.trim() !== (operation.description ?? '')) request.description = description.trim() || null;
  // Karşılaştırma dakika hassasiyetinde (giriş alanı saniye taşımaz).
  if (plannedIso !== originalPlanned) request.plannedEndAt = plannedIso ?? null;
  const changed = Object.keys(request).length > 0;

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="op-edit-title">
      <DialogTitle id="op-edit-title">Operasyonu düzenle</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <TextField
            label="Ad"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            error={!!(localErrors.name || serverErrors.name)}
            helperText={localErrors.name ?? serverErrors.name ?? `${trimmedName.length}/${NAME_MAX}`}
            inputProps={{ maxLength: NAME_MAX }}
          />
          <TextField
            label="Açıklama"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={3}
            error={!!(localErrors.description || serverErrors.description)}
            helperText={localErrors.description ?? serverErrors.description ?? `${description.trim().length}/${DESCRIPTION_MAX}`}
            inputProps={{ maxLength: DESCRIPTION_MAX }}
          />
          <TextField
            label="Planlanan bitiş"
            type="datetime-local"
            value={plannedEnd}
            onChange={(e) => setPlannedEnd(e.target.value)}
            InputLabelProps={{ shrink: true }}
            error={!!(localErrors.plannedEndAt || serverErrors.plannedEndAt)}
            helperText={
              localErrors.plannedEndAt ?? serverErrors.plannedEndAt ?? 'Türkiye saati; boş bırakılırsa planlanan bitiş kaldırılır'
            }
          />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>
          Vazgeç
        </Button>
        <Button
          variant="contained"
          disabled={mutation.isPending || !changed || Object.keys(localErrors).length > 0}
          onClick={() => mutation.mutate(request)}
        >
          {mutation.isPending ? 'Kaydediliyor…' : 'Kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
