/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `mock` → tüm servisler örnek veriyle (config/services.ts). */
  readonly VITE_SERVICE_PRESET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
