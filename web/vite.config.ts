/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Geliştirmede tüm API istekleri aynı köken üzerinden backend'e aktarılır (web-screen-api-mapping §1.1: CORS yok).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        secure: false,
      },
      // Telefon bildirimi (FCM) durumu için yalnızca sağlık ucu (ADR-022); diğer actuator uçları iletilmez.
      '^/actuator/health$': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        secure: false,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          mui: ['@mui/material', '@emotion/react', '@emotion/styled'],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    // Panel kabuğu (ADR-023) sonrası jsdom'da yoğun yazma testleri ~3 kat yavaşladı (user-event her işlemde
    // getComputedStyle ile pointer-events denetler; maliyet belgedeki stil kuralı sayısıyla artar). Gerçek tarayıcıda
    // bu maliyet yok; RAPOR_5'te not edildi.
    testTimeout: 15_000,
  },
});
