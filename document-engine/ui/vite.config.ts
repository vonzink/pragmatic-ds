import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

/**
 * Dev server runs on 6173 to match the CORS allowlist the engine configures for
 * its `local` profile (see app/.../config/PlatformConfig.java). The `/v1` proxy
 * makes the dev origin same-origin with the API, so CORS is only a fallback for
 * anyone who bypasses the proxy by pointing VITE_API_BASE at an absolute URL.
 */
const API_PROXY_TARGET = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:9090';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 6173,
    strictPort: true,
    proxy: {
      '/v1': {
        target: API_PROXY_TARGET,
        changeOrigin: false,
      },
    },
  },
  preview: {
    port: 6173,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
