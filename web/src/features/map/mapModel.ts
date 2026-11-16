import type { MapCircle, MapMarker } from '../../components/map/GksMap';
import type { MapOperationView, OperationState } from '../../services/types';

/** Durum renkleri (ADR-025): açık alarm her zaman kırmızı. Renk tek başına anlam taşımaz; etiket de verilir. */
export const MAP_COLORS: Record<OperationState | 'ALERT', string> = {
  ACTIVE: '#0B2D57',
  PLANNED: '#6B7280',
  SUSPENDED: '#C2410C',
  COMPLETED: '#6B7280',
  CANCELLED: '#6B7280',
  ALERT: '#B91C1C',
};
export const MEETING_POINT_COLOR = '#0369A1';
export const PERSON_COLOR = '#15803D';

/** Haritadaki konum: bölge merkezi, yoksa ilk buluşma noktası; ikisi de yoksa haritada yok (listede görünür). */
export function anchor(op: MapOperationView): { lat: number; lon: number } | null {
  if (op.area) return { lat: op.area.lat, lon: op.area.lon };
  const mp = op.meetingPoints[0];
  return mp ? { lat: mp.lat, lon: mp.lon } : null;
}

export function operationMarkers(items: MapOperationView[], stateLabel: (s: OperationState) => string): MapMarker[] {
  return items.flatMap((op) => {
    const a = anchor(op);
    if (!a) return [];
    const label = `${op.name} — ${stateLabel(op.state)}${op.openAlert ? ' — AÇIK ALARM' : ''}`;
    return [{ id: op.id, lat: a.lat, lon: a.lon, color: op.openAlert ? MAP_COLORS.ALERT : MAP_COLORS[op.state], label }];
  });
}

export function operationCircles(items: MapOperationView[]): MapCircle[] {
  return items.flatMap((op) =>
    op.area?.radiusM
      ? [{ id: op.id, lat: op.area.lat, lon: op.area.lon, radiusM: op.area.radiusM, color: op.openAlert ? MAP_COLORS.ALERT : MAP_COLORS[op.state] }]
      : [],
  );
}
