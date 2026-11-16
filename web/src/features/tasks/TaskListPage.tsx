import { useState } from 'react';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import WarningAmber from '@mui/icons-material/WarningAmber';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader, ReadOnlyBanner } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { TASK_PRIORITY_LABEL, TASK_STATE_LABEL, formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import { NoParticipantsHint } from '../operations/NoParticipantsHint';
import type { TaskCreateRequest, Uuid } from '../../services/types';
import { useOperationScope } from '../operations/useOperation';
import { TaskFormFields, emptyTaskForm, toCreateRequest, validateTaskForm, type TaskFormValues } from './TaskForm';

export function capacityLabel(accepted: number, capacity: number | undefined): string {
  return capacity === undefined ? `${accepted} / Sınırsız` : `${accepted}/${capacity}`;
}

/** WEB-06 görev listesi (TK-07) ve taslak oluşturma (TK-01). */
export function TaskListPage() {
  const { opId = '' } = useParams();
  const services = useServices();
  const mock = useIsMock('tasks');
  const scope = useOperationScope(opId);
  const [creating, setCreating] = useState(false);
  const query = useQuery({
    queryKey: Q.tasks(opId),
    queryFn: () => services.tasks.list(opId),
    enabled: !!opId,
    staleTime: 10_000,
  });
  const canManage = scope.can('task.manage');

  return (
    <>
      <PageHeader
        title={`Görevler${scope.operation.data ? ` — ${scope.operation.data.name}` : ''}`}
        mock={mock}
        actions={
          canManage ? (
            <Button variant="contained" onClick={() => setCreating(true)}>
              Görev oluştur
            </Button>
          ) : undefined
        }
      />
      <ReadOnlyBanner show={scope.admReadOnly} />
      <ClosedOperationBanner show={scope.closed} />
      {canManage && <NoParticipantsHint operationId={opId} purpose="Görevi kişilere veya gruplara atamak" />}
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Stack spacing={1} alignItems="flex-start">
              <Typography color="text.secondary">
                {canManage
                  ? 'Henüz görev yok. Görev taslak olarak oluşturulur; yayınlandığında hedef kişilere atama gider.'
                  : 'Bu operasyonda görev yok.'}
              </Typography>
              {canManage && (
                <Button variant="outlined" onClick={() => setCreating(true)}>
                  Görev oluştur
                </Button>
              )}
            </Stack>
          ) : (
            <TableContainer component={Paper}>
              <Table size="small" aria-label="Görev listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>Başlık</TableCell>
                    <TableCell>Durum</TableCell>
                    <TableCell>Öncelik</TableCell>
                    <TableCell>Kapasite</TableCell>
                    <TableCell>Bekleyen</TableCell>
                    <TableCell>Zaman</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {query.data!.items.map((t) => (
                    <TableRow key={t.id} hover>
                      <TableCell>
                        <RouterLink to={`/panel/operasyonlar/${opId}/gorevler/${t.id}`}>{t.title}</RouterLink>
                      </TableCell>
                      <TableCell>
                        <Chip size="small" label={TASK_STATE_LABEL[t.state]} />
                      </TableCell>
                      <TableCell>{TASK_PRIORITY_LABEL[t.priority]}</TableCell>
                      <TableCell>{capacityLabel(t.acceptedCount, t.capacity)}</TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={0.5} alignItems="center">
                          <span>{t.pendingCount ?? '—'}</span>
                          {!!t.overdueCount && (
                            <Chip
                              size="small"
                              color="warning"
                              icon={<WarningAmber fontSize="small" />}
                              label={`${t.overdueCount} süresi geçti`}
                            />
                          )}
                        </Stack>
                      </TableCell>
                      <TableCell>
                        {t.timeWindow?.start ? formatDateTime(t.timeWindow.start) : '—'}
                        {t.timeWindow?.end ? ` – ${formatDateTime(t.timeWindow.end)}` : ''}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {creating && <TaskCreateDialog operationId={opId} onClose={() => setCreating(false)} />}
    </>
  );
}

function TaskCreateDialog({ operationId, onClose }: { operationId: Uuid; onClose: () => void }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const intent = useIntentKey();
  const [values, setValues] = useState<TaskFormValues>(emptyTaskForm());
  const [touched, setTouched] = useState(false);
  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: TaskCreateRequest; key: string }) =>
      services.tasks.create(operationId, request, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (task) => {
      intent.reset();
      queryClient.setQueryData(Q.task(task.id), task);
      void queryClient.invalidateQueries({ queryKey: Q.tasksRoot(operationId) });
      navigate(`/panel/operasyonlar/${operationId}/gorevler/${task.id}`);
    },
  });
  const errors = validateTaskForm(values, { requireTargets: true });
  const server = fieldErrorMap(mutation.error);
  const shown = touched ? { ...server, ...errors } : server;

  function submit() {
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    const request = toCreateRequest(values);
    mutation.mutate({ request, key: intent.keyFor(request) });
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="task-create-title">
      <DialogTitle id="task-create-title">Görev oluştur (taslak)</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <TaskFormFields operationId={operationId} values={values} onChange={setValues} errors={shown} targetsEditable />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>
          Vazgeç
        </Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending}>
          {mutation.isPending ? 'Kaydediliyor…' : 'Taslak olarak kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
