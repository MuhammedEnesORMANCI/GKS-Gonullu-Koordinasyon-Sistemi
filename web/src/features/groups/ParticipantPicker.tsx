import Autocomplete from '@mui/material/Autocomplete';
import TextField from '@mui/material/TextField';
import { useQuery } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages';
import { Q } from '../../app/queryKeys';
import { useServices } from '../../services/registry';
import type { Page, ParticipantView, Uuid } from '../../services/types';

const PAGE_SIZE = 100;
const MAX_PAGES = 50;

type ParticipantLister = { list: (operationId: Uuid, p: { notInGroup?: Uuid; limit?: number; cursor?: string }) => Promise<Page<ParticipantView>> };

/**
 * Seçici adayları: tüm sayfalar okunur ve yalnızca aktif hesaplar döner (RAPOR_7 madde 1). Önceden tek sayfa
 * okunuyor ve sorgu anahtarı katılımcı listesiyle (varsayılan 50'lik ilk sayfa) paylaşılıyordu; ilk dolduran
 * ekran hangisiyse seçici onun sayfasını görüyordu. Devre dışı hesaplar sunucuda da reddedilir.
 */
export async function loadParticipantCandidates(service: ParticipantLister, operationId: Uuid, notInGroup?: Uuid) {
  const items: ParticipantView[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await service.list(operationId, { notInGroup, limit: PAGE_SIZE, cursor });
    items.push(...page.items.filter((p) => p.user.status === undefined || p.user.status === 'ACTIVE'));
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  const result: Page<ParticipantView> = { items };
  return result;
}

/** OR-06 tabanlı kişi seçici: yalnızca operasyon katılımcıları listelenir (AC-09.7). */
export function useParticipants(operationId: Uuid, notInGroup?: Uuid, enabled = true) {
  const services = useServices();
  return useQuery({
    queryKey: Q.participantCandidates(operationId, notInGroup),
    queryFn: () => loadParticipantCandidates(services.participants, operationId, notInGroup),
    enabled: enabled && !!operationId,
  });
}

function label(p: ParticipantView): string {
  return p.user.fullName ? `${p.user.displayName} — ${p.user.fullName}` : p.user.displayName;
}

export function ParticipantSelect({
  operationId,
  notInGroup,
  label: fieldLabel,
  value,
  onChange,
  exclude = [],
  required,
  error,
  helperText,
  disabled,
}: {
  operationId: Uuid;
  notInGroup?: Uuid;
  label: string;
  value: Uuid | null;
  onChange: (id: Uuid | null) => void;
  exclude?: Uuid[];
  required?: boolean;
  error?: boolean;
  helperText?: string;
  disabled?: boolean;
}) {
  const query = useParticipants(operationId, notInGroup);
  const options = (query.data?.items ?? []).filter((p) => !exclude.includes(p.user.id));
  const selected = options.find((p) => p.user.id === value) ?? null;
  return (
    <Autocomplete
      options={options}
      value={selected}
      loading={query.isPending}
      disabled={disabled}
      getOptionLabel={label}
      isOptionEqualToValue={(a, b) => a.user.id === b.user.id}
      onChange={(_, v) => onChange(v ? v.user.id : null)}
      noOptionsText="Uygun katılımcı yok — önce operasyona katılımcı ekleyin"
      renderInput={(params) => (
        <TextField
          {...params}
          label={fieldLabel}
          required={required}
          error={error || !!query.error}
          helperText={query.error ? `Katılımcılar alınamadı: ${messageFor(query.error) ?? ''}` : helperText}
        />
      )}
    />
  );
}

export function ParticipantMultiSelect({
  operationId,
  label: fieldLabel,
  value,
  onChange,
  exclude = [],
  max,
  helperText,
}: {
  operationId: Uuid;
  label: string;
  value: Uuid[];
  onChange: (ids: Uuid[]) => void;
  exclude?: Uuid[];
  max?: number;
  helperText?: string;
}) {
  const query = useParticipants(operationId);
  const options = (query.data?.items ?? []).filter((p) => !exclude.includes(p.user.id));
  const selected = options.filter((p) => value.includes(p.user.id));
  return (
    <Autocomplete
      multiple
      options={options}
      value={selected}
      loading={query.isPending}
      getOptionLabel={label}
      isOptionEqualToValue={(a, b) => a.user.id === b.user.id}
      getOptionDisabled={(o) => max !== undefined && value.length >= max && !value.includes(o.user.id)}
      onChange={(_, v) => onChange(v.map((p) => p.user.id))}
      noOptionsText="Uygun katılımcı yok — önce operasyona katılımcı ekleyin"
      renderInput={(params) => (
        <TextField
          {...params}
          label={fieldLabel}
          error={!!query.error}
          helperText={query.error ? `Katılımcılar alınamadı: ${messageFor(query.error) ?? ''}` : helperText}
        />
      )}
    />
  );
}
