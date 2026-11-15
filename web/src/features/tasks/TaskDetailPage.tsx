import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tabs from '@mui/material/Tabs';
import Typography from '@mui/material/Typography';
import WarningAmber from '@mui/icons-material/WarningAmber';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader, ReadOnlyBanner } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import {
  ASSIGNMENT_STATUS_LABEL,
  TASK_PRIORITY_LABEL,
  TASK_STATE_LABEL,
  formatDateTime,
} from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { AssignmentStatus, AssignmentView, TaskState, TaskStateRequest, TaskView, Uuid } from '../../services/types';
import { ParticipantSelect } from '../groups/ParticipantPicker';
import { useOperationScope } from '../operations/useOperation';
import { capacityLabel } from './TaskListPage';
import { TaskFormFields, taskToForm, toUpdateChanges, validateTaskForm, type TaskFormValues } from './TaskForm';

type StateTarget = TaskStateRequest['targetState'];

const STATE_ACTIONS: Record<TaskState, StateTarget[]> = {
  DRAFT: ['CANCELLED'],
  PUBLISHED: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};

const TABS: AssignmentStatus[] = ['PENDING', 'ACCEPTED', 'REJECTED', 'CAPACITY_FULL'];

const REFRESH_CODES = new Set([
  'INVALID_STATE_TRANSITION',
  'CAPACITY_FULL',
  'ASSIGNMENT_NOT_PENDING',
  'ALREADY_EXISTS',
  'NOT_A_PARTICIPANT',
  'OPERATION_CLOSED',
  'VERSION_CONFLICT',
  'NOT_FOUND',
]);

/** WEB-06 görev detayı: TK-08, TK-09, TK-02, TK-03, TK-04, TK-05, TK-06. */
export function TaskDetailPage() {
  const { opId = '', taskId = '' } = useParams();
  const services = useServices();
  const queryClient = useQueryClient();
  const mock = useIsMock('tasks');
  const scope = useOperationScope(opId);
  const publishKey = useIntentKey();
  const addKey = useIntentKey();
  const [tab, setTab] = useState<AssignmentStatus>('PENDING');
  const [editing, setEditing] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [stateTarget, setStateTarget] = useState<StateTarget | null>(null);
  const [removing, setRemoving] = useState<AssignmentView | null>(null);
  const [newAssignee, setNewAssignee] = useState<Uuid | null>(null);

  const query = useQuery({ queryKey: Q.task(taskId), queryFn: () => services.tasks.get(taskId), enabled: !!taskId, staleTime: 5_000 });
  const assignments = useQuery({
    queryKey: Q.taskAssignments(taskId, { status: [tab] }),
    queryFn: () => services.tasks.assignments(taskId, { status: [tab] }),
    enabled: !!taskId && !!query.data && query.data.state !== 'DRAFT',
  });

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: Q.task(taskId) }),
      queryClient.invalidateQueries({ queryKey: Q.tasksRoot(opId) }),
    ]);
  const onError = (e: unknown) => {
    if (isApiError(e) && REFRESH_CODES.has(e.code)) void refresh();
  };

  const publish = useMutation({
    mutationFn: ({ version, key }: { version: number; key: string }) => services.tasks.publish(taskId, version, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (task) => {
      publishKey.reset();
      setPublishing(false);
      queryClient.setQueryData(Q.task(taskId), task);
      void refresh();
    },
    onError,
  });

  const changeState = useMutation({
    mutationFn: async (targetState: StateTarget): Promise<TaskView> => {
      try {
        return await services.tasks.changeState(taskId, { targetState });
      } catch (e) {
        if (isApiError(e) && e.code === 'INVALID_STATE_TRANSITION') {
          const fresh = await services.tasks.get(taskId).catch(() => undefined);
          if (fresh) queryClient.setQueryData(Q.task(taskId), fresh);
          if (fresh?.state === targetState) return fresh;
        }
        throw e;
      }
    },
    onSuccess: (task) => {
      setStateTarget(null);
      queryClient.setQueryData(Q.task(taskId), task);
      void refresh();
    },
    onError,
  });

  const addAssignment = useMutation({
    mutationFn: ({ userId, key }: { userId: Uuid; key: string }) => services.tasks.addAssignment(taskId, userId, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: () => {
      addKey.reset();
      setNewAssignee(null);
      void refresh();
    },
    onError,
  });

  const removeAssignment = useMutation({
    mutationFn: (assignmentId: Uuid) => services.tasks.removeAssignment(taskId, assignmentId),
    onSuccess: () => {
      setRemoving(null);
      void refresh();
    },
    onError,
  });

  const task = query.data;
  const canManage = scope.can('task.manage');
  const editable = !!task && canManage && task.state !== 'DONE' && task.state !== 'CANCELLED';
  const live = task?.state === 'PUBLISHED' || task?.state === 'IN_PROGRESS';

  const stateLabel = (s: StateTarget) =>
    s === 'CANCELLED' ? (task?.state === 'DRAFT' ? 'Taslağı at' : 'İptal et') : s === 'IN_PROGRESS' ? 'Devam ediyor' : 'Tamamlandı';

  return (
    <>
      <PageHeader title={task?.title ?? 'Görev'} mock={mock} />
      <ReadOnlyBanner show={scope.admReadOnly} />
      <ClosedOperationBanner show={scope.closed} />
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() => (
          <Stack spacing={3}>
            <Paper sx={{ p: 2 }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Chip label={TASK_STATE_LABEL[task!.state]} size="small" />
                  <Chip label={`Öncelik: ${TASK_PRIORITY_LABEL[task!.priority]}`} size="small" variant="outlined" />
                  <Chip label={`Kapasite: ${capacityLabel(task!.acceptedCount, task!.capacity)}`} size="small" color="primary" variant="outlined" />
                </Stack>
                {task!.description && <Typography>{task!.description}</Typography>}
                <Typography variant="body2">
                  <strong>Yer:</strong> {task!.meetingPoint || '—'}
                </Typography>
                <Typography variant="body2">
                  <strong>Zaman:</strong> {formatDateTime(task!.timeWindow?.start) || '—'}
                  {task!.timeWindow?.end && ` – ${formatDateTime(task!.timeWindow.end)}`}
                </Typography>
                <Typography variant="body2">
                  <strong>Yanıt süresi:</strong> {task!.responseWindowMinutes} dk
                </Typography>
                {task!.targets && (
                  <Typography variant="body2">
                    <strong>Hedef:</strong> {task!.targets.groupIds?.length ?? 0} grup, {task!.targets.userIds?.length ?? 0} kişi
                  </Typography>
                )}
              </Stack>
            </Paper>

            {editable && (
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Button variant="outlined" onClick={() => setEditing(true)}>
                  Düzenle
                </Button>
                {task!.state === 'DRAFT' && (
                  <Button variant="contained" onClick={() => { publish.reset(); setPublishing(true); }}>
                    Yayınla
                  </Button>
                )}
                {STATE_ACTIONS[task!.state].map((s) => (
                  <Button
                    key={s}
                    variant="outlined"
                    color={s === 'CANCELLED' ? 'error' : 'primary'}
                    onClick={() => { changeState.reset(); setStateTarget(s); }}
                  >
                    {stateLabel(s)}
                  </Button>
                ))}
              </Stack>
            )}

            {task!.state !== 'DRAFT' && (
              <section aria-labelledby="assignments-heading">
                <Typography variant="h6" id="assignments-heading" mb={1}>
                  Atamalar
                </Typography>
                {canManage && live && (
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} mb={2} alignItems={{ sm: 'flex-start' }}>
                    <div style={{ flexGrow: 1, minWidth: 260 }}>
                      <ParticipantSelect operationId={opId} label="Kişi ata" value={newAssignee} onChange={setNewAssignee} />
                    </div>
                    <Button
                      variant="contained"
                      disabled={!newAssignee || addAssignment.isPending}
                      onClick={() => newAssignee && addAssignment.mutate({ userId: newAssignee, key: addKey.keyFor({ userId: newAssignee }) })}
                    >
                      {addAssignment.isPending ? 'Ekleniyor…' : 'Ata'}
                    </Button>
                  </Stack>
                )}
                <ErrorAlert error={addAssignment.error} />
                <Tabs value={tab} onChange={(_, v: AssignmentStatus) => setTab(v)} aria-label="Atama durumu">
                  {TABS.map((s) => (
                    <Tab key={s} value={s} label={ASSIGNMENT_STATUS_LABEL[s]} />
                  ))}
                </Tabs>
                <QueryState isPending={assignments.isPending} error={assignments.error} onRetry={() => void assignments.refetch()}>
                  {() =>
                    assignments.data!.items.length === 0 ? (
                      <Typography color="text.secondary" mt={2}>
                        Bu durumda atama yok.
                      </Typography>
                    ) : (
                      <TableContainer component={Paper}>
                        <Table size="small" aria-label="Atama tablosu">
                          <TableHead>
                            <TableRow>
                              <TableCell>Kişi</TableCell>
                              <TableCell>Durum</TableCell>
                              <TableCell>Yanıt son zamanı</TableCell>
                              <TableCell>Yanıt</TableCell>
                              {canManage && live && <TableCell align="right" />}
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {assignments.data!.items.map((a) => (
                              <TableRow key={a.id}>
                                <TableCell>{a.user.fullName ?? a.user.displayName}</TableCell>
                                <TableCell>
                                  <Stack direction="row" spacing={1} alignItems="center">
                                    <span>{ASSIGNMENT_STATUS_LABEL[a.status]}</span>
                                    {a.overdue && (
                                      <Chip size="small" color="warning" icon={<WarningAmber fontSize="small" />} label="Yanıt süresi geçti" />
                                    )}
                                  </Stack>
                                </TableCell>
                                <TableCell>{formatDateTime(a.respondBy)}</TableCell>
                                <TableCell>{formatDateTime(a.respondedAt) || '—'}</TableCell>
                                {canManage && live && (
                                  <TableCell align="right">
                                    <Button size="small" color="error" onClick={() => { removeAssignment.reset(); setRemoving(a); }}>
                                      Atamayı kaldır
                                    </Button>
                                  </TableCell>
                                )}
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </TableContainer>
                    )
                  }
                </QueryState>
              </section>
            )}

            {!!task!.history?.length && (
              <section aria-labelledby="history-heading">
                <Typography variant="h6" id="history-heading">
                  Durum geçmişi
                </Typography>
                <List dense>
                  {task!.history.map((h, i) => (
                    <ListItem key={i}>
                      <ListItemText primary={TASK_STATE_LABEL[h.state]} secondary={`${h.changedBy.displayName} · ${formatDateTime(h.changedAt)}`} />
                    </ListItem>
                  ))}
                </List>
              </section>
            )}
          </Stack>
        )}
      </QueryState>

      <ConfirmDialog
        open={publishing}
        title="Görevi yayınla"
        confirmLabel="Yayınla"
        pending={publish.isPending}
        error={<ErrorAlert error={publish.error} />}
        onClose={() => setPublishing(false)}
        onConfirm={() => task && publish.mutate({ version: task.version, key: publishKey.keyFor({ version: task.version }) })}
      >
        <Typography>
          Hedef: {task?.targets?.groupIds?.length ?? 0} grup, {task?.targets?.userIds?.length ?? 0} kişi.
        </Typography>
        <Typography>Yayın anındaki aktif üyelere atama oluşur ve bildirim gider.</Typography>
      </ConfirmDialog>

      <ConfirmDialog
        open={stateTarget !== null}
        title={stateTarget ? stateLabel(stateTarget) : ''}
        confirmLabel={stateTarget ? stateLabel(stateTarget) : 'Onayla'}
        destructive={stateTarget === 'CANCELLED'}
        pending={changeState.isPending}
        error={<ErrorAlert error={changeState.error} />}
        onClose={() => setStateTarget(null)}
        onConfirm={() => stateTarget && changeState.mutate(stateTarget)}
      >
        <Typography>
          {stateTarget === 'CANCELLED'
            ? task?.state === 'DRAFT'
              ? 'Taslak iptal edilir; kayıt silinmez.'
              : 'Görev iptal edilir; atanan kişilere bildirim gider.'
            : `Görev durumu "${stateTarget ? stateLabel(stateTarget) : ''}" olarak değişecek.`}
        </Typography>
      </ConfirmDialog>

      <ConfirmDialog
        open={removing !== null}
        title="Atamayı kaldır"
        confirmLabel="Kaldır"
        destructive
        pending={removeAssignment.isPending}
        error={<ErrorAlert error={removeAssignment.error} />}
        onClose={() => setRemoving(null)}
        onConfirm={() => removing && removeAssignment.mutate(removing.id)}
      >
        <Typography>{removing?.user.displayName} kişisinin ataması kaldırılsın mı? Kişi bildirim alır.</Typography>
      </ConfirmDialog>

      {editing && task && (
        <TaskEditDialog
          operationId={opId}
          task={task}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            queryClient.setQueryData(Q.task(taskId), saved);
            setEditing(false);
            void refresh();
          }}
        />
      )}
    </>
  );
}

function TaskEditDialog({
  operationId,
  task,
  onClose,
  onSaved,
}: {
  operationId: Uuid;
  task: TaskView;
  onClose: () => void;
  onSaved: (task: TaskView) => void;
}) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [values, setValues] = useState<TaskFormValues>(taskToForm(task));
  /** Kullanıcının üzerine çalıştığı sunucu sürümü; VERSION_CONFLICT sonrası güncellenir, girdi korunur. */
  const [base, setBase] = useState<TaskView>(task);
  const [hints, setHints] = useState<Record<string, string>>({});
  const allowTargets = base.state === 'DRAFT';

  const mutation = useMutation({
    mutationFn: () => services.tasks.update(task.id, { ...toUpdateChanges(values, base, allowTargets), version: base.version }),
    onSuccess: onSaved,
    onError: async (e) => {
      if (isApiError(e) && e.code === 'VERSION_CONFLICT') {
        const fresh = await services.tasks.get(task.id).catch(() => undefined);
        if (!fresh) return;
        queryClient.setQueryData(Q.task(task.id), fresh);
        // Farklı alanları işaretle; kullanıcı girdisi korunur, otomatik birleştirme yok (§6.3).
        const server = taskToForm(fresh);
        const next: Record<string, string> = {};
        (Object.keys(server) as Array<keyof TaskFormValues>).forEach((k) => {
          if (JSON.stringify(server[k]) !== JSON.stringify(values[k])) {
            next[k] = `Sunucudaki güncel değer: ${Array.isArray(server[k]) ? `${(server[k] as string[]).length} seçim` : server[k] || '(boş)'}`;
          }
        });
        setHints(next);
        setBase(fresh);
      }
    },
  });

  const errors = validateTaskForm(values, { requireTargets: allowTargets });
  const server = fieldErrorMap(mutation.error);
  const changes = toUpdateChanges(values, base, allowTargets);
  const capacityError =
    isApiError(mutation.error) && mutation.error.code === 'CAPACITY_BELOW_ACCEPTED'
      ? `Kabul eden kişi sayısı: ${mutation.error.extension<number>('acceptedCount') ?? base.acceptedCount}`
      : undefined;
  const shownErrors = { ...server, ...errors, ...(capacityError ? { capacity: capacityError } : {}) };
  const assignedCount = base.acceptedCount + (base.pendingCount ?? 0);

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="task-edit-title">
      <DialogTitle id="task-edit-title">Görevi düzenle</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          {base.state !== 'DRAFT' && (
            <Alert severity="info">Atanan {assignedCount} kişiye değişiklik bildirimi gider. Hedefler yayından sonra değiştirilemez.</Alert>
          )}
          <TaskFormFields
            operationId={operationId}
            values={values}
            onChange={setValues}
            errors={shownErrors}
            targetsEditable={allowTargets}
            serverHints={hints}
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
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending || Object.keys(errors).length > 0 || Object.keys(changes).length === 0}
        >
          {mutation.isPending ? 'Kaydediliyor…' : 'Kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
