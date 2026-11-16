/**
 * Harita yapılandırması (ADR-025). Karo adresi yapılandırmadan gelir. OSM'nin varsayılan karo sunucusu yalnızca
 * geliştirme ve test içindir (OSMF karo kullanım politikası); üretimde kurumun kendi karo sunucusu verilmelidir.
 */
export const OSM_DEV_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

export const MAP_CONFIG = {
  tileUrl: (import.meta.env.VITE_MAP_TILE_URL as string | undefined) || OSM_DEV_TILE_URL,
  attribution: (import.meta.env.VITE_MAP_ATTRIBUTION as string | undefined) || '© OpenStreetMap katkıcıları',
  /** Türkiye'nin ortası; işaret yokken başlangıç görünümü. */
  defaultCenter: { lat: 39.0, lon: 35.2 },
  defaultZoom: 5,
  maxZoom: 19,
} as const;
