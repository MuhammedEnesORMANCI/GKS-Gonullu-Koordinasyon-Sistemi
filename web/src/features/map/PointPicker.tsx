import { useMemo } from 'react';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { GksMap } from '../../components/map/GksMap';
import { parseCoordinate, validLat, validLon } from '../../components/map/geo';
import type { EventLocation } from '../../services/types';
import { MAP_COLORS } from './mapModel';

export interface PointDraft {
  lat: string;
  lon: string;
}

export const EMPTY_POINT: PointDraft = { lat: '', lon: '' };

/** Boş → `null`; hatalıysa hata iletisi. */
export function parsePoint(d: PointDraft): { point: EventLocation | null; error?: string } {
  const lat = parseCoordinate(d.lat);
  const lon = parseCoordinate(d.lon);
  if (lat === undefined && lon === undefined) return { point: null };
  if (lat === undefined || lon === undefined) return { point: null, error: 'Enlem ve boylam birlikte girilmelidir.' };
  if (!validLat(lat)) return { point: null, error: 'Enlem −90 ile 90 arasında olmalı.' };
  if (!validLon(lon)) return { point: null, error: 'Boylam −180 ile 180 arasında olmalı.' };
  return { point: { lat, lon } };
}

/** İsteğe bağlı olay yeri seçimi (ADR-026): haritaya tıklayarak ya da elle. */
export function PointPicker({ label, value, onChange }: { label: string; value: PointDraft; onChange: (d: PointDraft) => void }) {
  const { point, error } = parsePoint(value);
  const lat = point?.lat;
  const lon = point?.lon;
  const markers = useMemo(
    () => (lat !== undefined && lon !== undefined ? [{ id: 'event', lat, lon, color: MAP_COLORS.ALERT, label }] : []),
    [lat, lon, label],
  );
  return (
    <Stack spacing={1} role="group" aria-label={label}>
      <Typography variant="subtitle2">{label} (isteğe bağlı)</Typography>
      <GksMap
        markers={markers}
        height={200}
        ariaLabel={`${label} seçim haritası`}
        onPick={(lat, lon) => onChange({ lat: lat.toFixed(6), lon: lon.toFixed(6) })}
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField
          label="Olay yeri enlem"
          value={value.lat}
          onChange={(e) => onChange({ ...value, lat: e.target.value })}
          error={!!error}
          helperText={error ?? ' '}
          inputProps={{ inputMode: 'decimal' }}
        />
        <TextField
          label="Olay yeri boylam"
          value={value.lon}
          onChange={(e) => onChange({ ...value, lon: e.target.value })}
          error={!!error}
          inputProps={{ inputMode: 'decimal' }}
        />
        {(value.lat || value.lon) && (
          <Button size="small" onClick={() => onChange(EMPTY_POINT)}>
            Temizle
          </Button>
        )}
      </Stack>
    </Stack>
  );
}
