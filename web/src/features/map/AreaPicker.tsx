import { useMemo, useState } from 'react';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { GksMap } from '../../components/map/GksMap';
import { parseCoordinate, validLat, validLon } from '../../components/map/geo';
import type { OperationArea } from '../../services/types';
import { MAP_COLORS } from './mapModel';

export const RADIUS_MIN = 50;
export const RADIUS_MAX = 200_000;

export interface AreaDraft {
  lat: string;
  lon: string;
  radius: string;
}

export function areaDraft(area?: OperationArea | null): AreaDraft {
  return area
    ? { lat: String(area.lat), lon: String(area.lon), radius: area.radiusM !== undefined ? String(area.radiusM) : '' }
    : { lat: '', lon: '', radius: '' };
}

/** Taslağı doğrular: boş → `null` (bölge yok); hatalıysa alan → ileti. */
export function parseArea(d: AreaDraft): { area: OperationArea | null; errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  const lat = parseCoordinate(d.lat);
  const lon = parseCoordinate(d.lon);
  const radius = d.radius.trim() === '' ? undefined : Number(d.radius.trim());
  if (lat === undefined && lon === undefined) {
    if (radius !== undefined) errors['area.radiusM'] = 'Yarıçap için önce merkez seçin.';
    return { area: null, errors };
  }
  if (lat === undefined || lon === undefined) errors['area.lat'] = 'Enlem ve boylam birlikte girilmelidir.';
  else {
    if (!validLat(lat)) errors['area.lat'] = 'Enlem −90 ile 90 arasında olmalı.';
    if (!validLon(lon)) errors['area.lon'] = 'Boylam −180 ile 180 arasında olmalı.';
  }
  if (radius !== undefined && (!Number.isInteger(radius) || radius < RADIUS_MIN || radius > RADIUS_MAX)) {
    errors['area.radiusM'] = `Yarıçap ${RADIUS_MIN}–${RADIUS_MAX} metre arasında tam sayı olmalı.`;
  }
  if (Object.keys(errors).length > 0 || lat === undefined || lon === undefined) return { area: null, errors };
  return { area: { lat, lon, ...(radius !== undefined ? { radiusM: radius } : {}) }, errors };
}

/** Operasyon bölgesi seçimi (ADR-025): haritaya tıklayarak ya da koordinatı elle girerek; yarıçap isteğe bağlı. */
export function AreaPicker({
  value,
  onChange,
  serverErrors = {},
}: {
  value: AreaDraft;
  onChange: (d: AreaDraft) => void;
  serverErrors?: Record<string, string>;
}) {
  const { area, errors } = parseArea(value);
  const shown = { ...errors, ...serverErrors };
  const [touched, setTouched] = useState(false);
  const markers = useMemo(
    () => (area ? [{ id: 'center', lat: area.lat, lon: area.lon, color: MAP_COLORS.ACTIVE, label: 'Operasyon merkezi' }] : []),
    [area?.lat, area?.lon],
  );
  const circles = useMemo(
    () => (area?.radiusM ? [{ id: 'area', lat: area.lat, lon: area.lon, radiusM: area.radiusM, color: MAP_COLORS.ACTIVE }] : []),
    [area?.lat, area?.lon, area?.radiusM], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const show = (k: string) => (touched || serverErrors[k] ? shown[k] : undefined);
  return (
    <Stack spacing={1} role="group" aria-label="Operasyon bölgesi">
      <Typography variant="subtitle2">Operasyon bölgesi (isteğe bağlı)</Typography>
      <GksMap
        markers={markers}
        circles={circles}
        height={240}
        ariaLabel="Bölge seçim haritası"
        onPick={(lat, lon) => onChange({ ...value, lat: lat.toFixed(6), lon: lon.toFixed(6) })}
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField
          label="Merkez enlem"
          value={value.lat}
          onChange={(e) => onChange({ ...value, lat: e.target.value })}
          onBlur={() => setTouched(true)}
          error={!!show('area.lat')}
          helperText={show('area.lat') ?? ' '}
          inputProps={{ inputMode: 'decimal' }}
        />
        <TextField
          label="Merkez boylam"
          value={value.lon}
          onChange={(e) => onChange({ ...value, lon: e.target.value })}
          onBlur={() => setTouched(true)}
          error={!!show('area.lon')}
          helperText={show('area.lon') ?? ' '}
          inputProps={{ inputMode: 'decimal' }}
        />
        <TextField
          label="Yarıçap (m)"
          value={value.radius}
          onChange={(e) => onChange({ ...value, radius: e.target.value })}
          onBlur={() => setTouched(true)}
          error={!!show('area.radiusM')}
          helperText={show('area.radiusM') ?? 'İsteğe bağlı'}
          inputProps={{ inputMode: 'numeric' }}
        />
      </Stack>
      {(value.lat || value.lon || value.radius) && (
        <Button size="small" onClick={() => onChange({ lat: '', lon: '', radius: '' })} sx={{ alignSelf: 'flex-start' }}>
          Bölgeyi temizle
        </Button>
      )}
    </Stack>
  );
}
