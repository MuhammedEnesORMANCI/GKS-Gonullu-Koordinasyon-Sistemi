/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `mock` → tüm servisler örnek veriyle (config/services.ts). */
  readonly VITE_SERVICE_PRESET?: string;
  /** ADR-025: harita karo adresi ({z}/{x}/{y}); verilmezse yalnızca geliştirme/test için OSM varsayılan sunucusu. */
  readonly VITE_MAP_TILE_URL?: string;
  /** Karo sunucusunun atıf metni (varsayılan "© OpenStreetMap katkıcıları"). */
  readonly VITE_MAP_ATTRIBUTION?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
