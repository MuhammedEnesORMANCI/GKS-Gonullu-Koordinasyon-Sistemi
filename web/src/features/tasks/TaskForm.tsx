import Autocomplete from '@mui/material/Autocomplete';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { TASK_PRIORITY_LABEL, fromLocalInput, toLocalInput } from '../../components/format';
import { useServices } from '../../services/registry';
import type { TaskCreateRequest, TaskPriority, TaskUpdateChanges, TaskView, Uuid } from '../../services/types';
import { ParticipantMultiSelect } from '../groups/ParticipantPicker';

export const TASK_TITLE_MIN = 3;
export const TASK_TITLE_MAX = 120;
export const TASK_DESCRIPTION_MAX = 2000;
export const TASK_PLACE_MAX = 300;
export const CAPACITY_MIN = 1;
export const CAPACITY_MAX = 500;
export const WINDOW_MIN = 5;
export const WINDOW_MAX = 1440;

/** Form değerleri metin olarak tutulur; istek gövdesine `toRequest` ile çevrilir (backend §9.3). */
export interface TaskFormValues {
  title: string;
  description: string;
  priority: TaskPriority;
  meetingPoint: string;
  start: string;
  end: string;
  capacity: string;
  responseWindowMinutes: string;
  userIds: Uuid[];
  groupIds: Uuid[];
}

export function emptyTaskForm(): TaskFormValues {
  return {
    title: '',
    description: '',
    priority: 'NORMAL',
    meetingPoint: '',
    start: '',
    end: '',
    capacity: '',
    responseWindowMinutes: '60',
    userIds: [],
    groupIds: [],
  };
}

export function taskToForm(task: TaskView): TaskFormValues {
  return {
    title: task.title,
    description: task.description ?? '',
    priority: task.priority,
    meetingPoint: task.meetingPoint ?? '',
    start: toLocalInput(task.timeWindow?.start),
    end: toLocalInput(task.timeWindow?.end),
    capacity: task.capacity !== undefined ? String(task.capacity) : '',
    responseWindowMinutes: String(task.responseWindowMinutes),
    userIds: task.targets?.userIds ?? [],
    groupIds: task.targets?.groupIds ?? [],
  };
}

const isInt = (s: string) => /^\d+$/.test(s.trim());

export function validateTaskForm(v: TaskFormValues, { requireTargets }: { requireTargets: boolean }): Record<string, string> {
  const e: Record<string, string> = {};
  const title = v.title.trim();
  if (title.length < TASK_TITLE_MIN || title.length > TASK_TITLE_MAX) {
    e.title = `Başlık ${TASK_TITLE_MIN}–${TASK_TITLE_MAX} karakter olmalı.`;
  }
  if (v.description.trim().length > TASK_DESCRIPTION_MAX) e.description = `En fazla ${TASK_DESCRIPTION_MAX} karakter.`;
  if (v.meetingPoint.trim().length > TASK_PLACE_MAX) e.meetingPoint = `En fazla ${TASK_PLACE_MAX} karakter.`;
  const start = fromLocalInput(v.start);
  const end = fromLocalInput(v.end);
  // DB ck_tasks_time_window: başlangıcı olmayan aralık reddedilir (backend 400 `timeWindow.start` REQUIRED).
  if (end && !start) e.start = 'Bitiş girildiyse başlangıç zorunludur.';
  if (start && end && new Date(start).getTime() >= new Date(end).getTime()) e.end = 'Bitiş, başlangıçtan sonra olmalı.';
  if (v.capacity.trim()) {
    const n = Number(v.capacity);
    if (!isInt(v.capacity) || n < CAPACITY_MIN || n > CAPACITY_MAX) e.capacity = `${CAPACITY_MIN}–${CAPACITY_MAX} arasında tam sayı.`;
  }
  if (v.responseWindowMinutes.trim()) {
    const n = Number(v.responseWindowMinutes);
    if (!isInt(v.responseWindowMinutes) || n < WINDOW_MIN || n > WINDOW_MAX) {
      e.responseWindowMinutes = `${WINDOW_MIN}–${WINDOW_MAX} dakika.`;
    }
  }
  if (requireTargets && v.userIds.length + v.groupIds.length === 0) e.targets = 'En az bir kişi veya grup seçin.';
  return e;
}

/** Oluşturma gövdesi (TK-01). Boş isteğe bağlı alanlar gönderilmez. */
export function toCreateRequest(v: TaskFormValues): TaskCreateRequest {
  const request: TaskCreateRequest = {
    title: v.title.trim(),
    priority: v.priority,
    targets: { userIds: v.userIds, groupIds: v.groupIds },
  };
  if (v.description.trim()) request.description = v.description.trim();
  if (v.meetingPoint.trim()) request.meetingPoint = v.meetingPoint.trim();
  const start = fromLocalInput(v.start);
  const end = fromLocalInput(v.end);
  if (start || end) request.timeWindow = { ...(start ? { start } : {}), ...(end ? { end } : {}) };
  if (v.capacity.trim()) request.capacity = Number(v.capacity);
  if (v.responseWindowMinutes.trim()) request.responseWindowMinutes = Number(v.responseWindowMinutes);
  return request;
}

/**
 * Düzenleme gövdesi (TK-02, LD-52): yalnızca değişen alanlar; boşaltılan isteğe bağlı alan `null` ile temizlenir
 * (`capacity: null` = sınırsız). `targets` yalnızca taslakta.
 */
export function toUpdateChanges(v: TaskFormValues, task: TaskView, allowTargets: boolean): TaskUpdateChanges {
  const next = toCreateRequest(v);
  const original = toCreateRequest(taskToForm(task));
  const changes: TaskUpdateChanges = {};
  if (next.title !== task.title) changes.title = next.title;
  if (next.description !== original.description) changes.description = next.description ?? null;
  if (next.priority !== task.priority) changes.priority = next.priority;
  if (next.meetingPoint !== original.meetingPoint) changes.meetingPoint = next.meetingPoint ?? null;
  // Karşılaştırma form hassasiyetinde (dakika), sunucudaki saniyeler sahte değişiklik üretmesin.
  if (JSON.stringify(next.timeWindow ?? null) !== JSON.stringify(original.timeWindow ?? null)) {
    changes.timeWindow = next.timeWindow ?? null;
  }
  if (next.capacity !== task.capacity) changes.capacity = next.capacity ?? null;
  if (next.responseWindowMinutes !== task.responseWindowMinutes) {
    changes.responseWindowMinutes = next.responseWindowMinutes ?? null;
  }
  if (allowTargets) {
    const sortJ = (a?: string[]) => JSON.stringify([...(a ?? [])].sort());
    if (
      sortJ(next.targets.userIds) !== sortJ(task.targets?.userIds) ||
      sortJ(next.targets.groupIds) !== sortJ(task.targets?.groupIds)
    ) {
      changes.targets = next.targets;
    }
  }
  return changes;
}

export function TaskFormFields({
  operationId,
  values,
  onChange,
  errors,
  targetsEditable,
  serverHints = {},
}: {
  operationId: Uuid;
  values: TaskFormValues;
  onChange: (v: TaskFormValues) => void;
  errors: Record<string, string>;
  targetsEditable: boolean;
  /** VERSION_CONFLICT sonrası: sunucudaki farklı değerler (alan → açıklama). */
  serverHints?: Record<string, string>;
}) {
  const services = useServices();
  const groups = useQuery({
    queryKey: [...Q.groups(operationId), { includeArchived: false }],
    queryFn: () => services.groups.list(operationId, {}),
    enabled: targetsEditable,
  });
  const set = <K extends keyof TaskFormValues>(key: K, value: TaskFormValues[K]) => onChange({ ...values, [key]: value });
  // Sunucu alan yolları (`timeWindow.start`) form alanlarına eşlenir.
  const err = (field: string) => errors[field] ?? (field === 'start' || field === 'end' ? errors[`timeWindow.${field}`] : undefined);
  const help = (field: string, fallback?: string) => err(field) ?? serverHints[field] ?? fallback;
  const groupOptions = groups.data?.items ?? [];

  return (
    <Stack spacing={2}>
      <TextField
        label="Başlık"
        value={values.title}
        onChange={(e) => set('title', e.target.value)}
        required
        error={!!errors.title}
        helperText={help('title', `${values.title.trim().length}/${TASK_TITLE_MAX}`)}
        inputProps={{ maxLength: TASK_TITLE_MAX }}
      />
      <TextField
        label="Açıklama"
        value={values.description}
        onChange={(e) => set('description', e.target.value)}
        multiline
        minRows={2}
        error={!!errors.description}
        helperText={help('description', `${values.description.trim().length}/${TASK_DESCRIPTION_MAX}`)}
        inputProps={{ maxLength: TASK_DESCRIPTION_MAX }}
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Öncelik"
          value={values.priority}
          onChange={(e) => set('priority', e.target.value as TaskPriority)}
          helperText={serverHints.priority}
          sx={{ minWidth: 160 }}
        >
          {(Object.keys(TASK_PRIORITY_LABEL) as TaskPriority[]).map((p) => (
            <MenuItem key={p} value={p}>
              {TASK_PRIORITY_LABEL[p]}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Yer / buluşma noktası"
          value={values.meetingPoint}
          onChange={(e) => set('meetingPoint', e.target.value)}
          error={!!errors.meetingPoint}
          helperText={help('meetingPoint')}
          inputProps={{ maxLength: TASK_PLACE_MAX }}
          fullWidth
        />
      </Stack>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Başlangıç"
          type="datetime-local"
          value={values.start}
          onChange={(e) => set('start', e.target.value)}
          InputLabelProps={{ shrink: true }}
          error={!!err('start')}
          helperText={help('start')}
          fullWidth
        />
        <TextField
          label="Bitiş"
          type="datetime-local"
          value={values.end}
          onChange={(e) => set('end', e.target.value)}
          InputLabelProps={{ shrink: true }}
          error={!!err('end')}
          helperText={help('end')}
          fullWidth
        />
      </Stack>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Kapasite"
          value={values.capacity}
          onChange={(e) => set('capacity', e.target.value)}
          error={!!errors.capacity}
          helperText={help('capacity', 'Boş = sınırsız')}
          inputProps={{ inputMode: 'numeric' }}
          fullWidth
        />
        <TextField
          label="Yanıt süresi (dk)"
          value={values.responseWindowMinutes}
          onChange={(e) => set('responseWindowMinutes', e.target.value)}
          error={!!errors.responseWindowMinutes}
          helperText={help('responseWindowMinutes', `${WINDOW_MIN}–${WINDOW_MAX}, varsayılan 60`)}
          inputProps={{ inputMode: 'numeric' }}
          fullWidth
        />
      </Stack>
      {targetsEditable && (
        <>
          <Autocomplete
            multiple
            options={groupOptions}
            loading={groups.isPending}
            value={groupOptions.filter((g) => values.groupIds.includes(g.id))}
            getOptionLabel={(g) => `${g.name} (${g.memberCount} üye)`}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            onChange={(_, v) => set('groupIds', v.map((g) => g.id))}
            noOptionsText="Grup yok"
            renderInput={(params) => (
              <TextField {...params} label="Hedef gruplar" error={!!errors.targets} helperText={errors.targets} />
            )}
          />
          <ParticipantMultiSelect
            operationId={operationId}
            label="Hedef kişiler"
            value={values.userIds}
            onChange={(ids) => set('userIds', ids)}
            helperText="Yalnızca operasyon katılımcıları."
          />
        </>
      )}
    </Stack>
  );
}
