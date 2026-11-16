/** Koordinat yardımcıları (ADR-025); harita kütüphanesinden bağımsız, birim testli. */

export interface LatLon {
  lat: number;
  lon: number;
}

const EARTH_RADIUS_M = 6_371_008.8;

/** Boş → undefined; virgüllü ondalık kabul edilir; sayı değilse NaN. */
export function parseCoordinate(value: string): number | undefined {
  if (value.trim() === '') return undefined;
  const n = Number(value.trim().replace(',', '.'));
  return Number.isFinite(n) ? n : Number.NaN;
}

export function validLat(n: number | undefined): n is number {
  return n !== undefined && Number.isFinite(n) && n >= -90 && n <= 90;
}

export function validLon(n: number | undefined): n is number {
  return n !== undefined && Number.isFinite(n) && n >= -180 && n <= 180;
}

export function formatLatLon(lat: number, lon: number): string {
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
}

/** Yarıçaplı alanı çizmek için daireyi çokgene çevirir (GeoJSON sırası: [boylam, enlem]); halka kapalıdır. */
export function circlePolygon(center: LatLon, radiusM: number, steps = 64): [number, number][] {
  const lat1 = (center.lat * Math.PI) / 180;
  const lon1 = (center.lon * Math.PI) / 180;
  const d = radiusM / EARTH_RADIUS_M;
  const ring: [number, number][] = [];
  for (let i = 0; i <= steps; i++) {
    const bearing = (2 * Math.PI * (i % steps)) / steps;
    const lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing));
    const lon2 =
      lon1 + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
    ring.push([(lon2 * 180) / Math.PI, (lat2 * 180) / Math.PI]);
  }
  return ring;
}

/** İki nokta arası büyük daire mesafesi (metre). */
export function distanceM(a: LatLon, b: LatLon): number {
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLon = ((b.lon - a.lon) * Math.PI) / 180;
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

/** Tüm noktaları (ve dairelerin kenarlarını) kapsayan sınır: [[batı, güney], [doğu, kuzey]]. */
export function bounds(points: LatLon[], circles: { center: LatLon; radiusM: number }[] = []): [[number, number], [number, number]] | null {
  const all = [...points];
  for (const c of circles) for (const [lon, lat] of circlePolygon(c.center, c.radiusM, 16)) all.push({ lat, lon });
  if (all.length === 0) return null;
  let w = Infinity;
  let s = Infinity;
  let e = -Infinity;
  let n = -Infinity;
  for (const p of all) {
    w = Math.min(w, p.lon);
    e = Math.max(e, p.lon);
    s = Math.min(s, p.lat);
    n = Math.max(n, p.lat);
  }
  return [
    [w, s],
    [e, n],
  ];
}
