import Chip, { type ChipProps } from '@mui/material/Chip';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import { ALERT_STATE_COLOR, ALERT_STATE_LABEL } from '../../features/alerts/alertLabels';
import type { AlertState, OperationState, PublicAnnouncementState, TaskState } from '../../services/types';
import { OPERATION_STATE_COLOR, OPERATION_STATE_LABEL, TASK_STATE_LABEL } from '../format';

type Tone = 'default' | 'success' | 'warning' | 'info' | 'error';

const TASK_STATE_COLOR: Record<TaskState, Tone> = {
  DRAFT: 'default',
  PUBLISHED: 'info',
  IN_PROGRESS: 'warning',
  DONE: 'success',
  CANCELLED: 'default',
};

const PUBLIC_COLOR: Record<PublicAnnouncementState, Tone> = { DRAFT: 'default', PUBLISHED: 'success' };
const PUBLIC_LABEL: Record<PublicAnnouncementState, string> = { DRAFT: 'Taslak', PUBLISHED: 'Yayında' };

export type StatusChipProps =
  | { kind: 'operation'; value: OperationState }
  | { kind: 'alert'; value: AlertState }
  | { kind: 'task'; value: TaskState }
  | { kind: 'publicAnnouncement'; value: PublicAnnouncementState };

/** Tek durum rozeti: aynı durum her ekranda aynı renk ve etiketle (ADR-023). */
export function statusOf(props: StatusChipProps): { label: string; tone: Tone } {
  switch (props.kind) {
    case 'operation':
      return { label: OPERATION_STATE_LABEL[props.value], tone: OPERATION_STATE_COLOR[props.value] };
    case 'alert':
      return { label: ALERT_STATE_LABEL[props.value], tone: ALERT_STATE_COLOR[props.value] };
    case 'task':
      return { label: TASK_STATE_LABEL[props.value], tone: TASK_STATE_COLOR[props.value] };
    case 'publicAnnouncement':
      return { label: PUBLIC_LABEL[props.value], tone: PUBLIC_COLOR[props.value] };
  }
}

export function StatusChip(props: StatusChipProps & { size?: ChipProps['size'] }) {
  const { label, tone } = statusOf(props);
  return (
    <Chip
      size={props.size ?? 'small'}
      label={label}
      color={tone}
      icon={<FiberManualRecordIcon sx={{ fontSize: '0.75rem !important' }} />}
      variant={tone === 'default' ? 'outlined' : 'filled'}
    />
  );
}
