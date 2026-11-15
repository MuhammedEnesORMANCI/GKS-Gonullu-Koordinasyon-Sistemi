import type { AssignmentStatus, OperationState, TaskPriority, TaskState } from '../services/types';

/** Gösterim Europe/Istanbul (backend §3.1, VS-21). */
const dateTimeFormat = new Intl.DateTimeFormat('tr-TR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Europe/Istanbul',
});

export function formatDateTime(value: string | undefined): string {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : dateTimeFormat.format(d);
}

/** ISO → `<input type="datetime-local">` değeri (Europe/Istanbul yerel saat). */
export function toLocalInput(value: string | undefined): string {
  if (!value) return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Istanbul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(d);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`;
}

/** `<input type="datetime-local">` değeri (Europe/Istanbul, UTC+3 sabit) → ISO-8601 UTC. */
export function fromLocalInput(value: string): string | undefined {
  if (!value) return undefined;
  // Türkiye 2016'dan beri yaz saati uygulamıyor: sabit +03:00.
  const d = new Date(`${value}:00+03:00`);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

export const OPERATION_STATE_LABEL: Record<OperationState, string> = {
  PLANNED: 'Planlandı',
  ACTIVE: 'Aktif',
  SUSPENDED: 'Askıda',
  COMPLETED: 'Tamamlandı',
  CANCELLED: 'İptal edildi',
};

export const OPERATION_STATE_COLOR: Record<OperationState, 'default' | 'success' | 'warning' | 'info' | 'error'> = {
  PLANNED: 'info',
  ACTIVE: 'success',
  SUSPENDED: 'warning',
  COMPLETED: 'default',
  CANCELLED: 'error',
};

export const TASK_STATE_LABEL: Record<TaskState, string> = {
  DRAFT: 'Taslak',
  PUBLISHED: 'Yayında',
  IN_PROGRESS: 'Devam ediyor',
  DONE: 'Tamamlandı',
  CANCELLED: 'İptal',
};

export const TASK_PRIORITY_LABEL: Record<TaskPriority, string> = {
  LOW: 'Düşük',
  NORMAL: 'Normal',
  HIGH: 'Yüksek',
  URGENT: 'Acil',
};

export const ASSIGNMENT_STATUS_LABEL: Record<AssignmentStatus, string> = {
  PENDING: 'Bekliyor',
  ACCEPTED: 'Kabul',
  REJECTED: 'Ret',
  CAPACITY_FULL: 'Kapasite doldu',
  REMOVED: 'Kaldırıldı',
};

export function isClosedState(state: OperationState | undefined): boolean {
  return state === 'COMPLETED' || state === 'CANCELLED';
}
