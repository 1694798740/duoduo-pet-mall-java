import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 平台管理后台（E7-2：**独立应用**）。
 *
 * 与商城应用的关系：源码、构建、产物**三者都分开** ——
 * 本应用的入口只有 `admin.html`，产出自己的 `dist/`，商城产物里不再有这个入口的任何痕迹。
 * 共用层（`packages/api-sdk`、`packages/shared`）不预构建，直接吃 TS 源码。
 *
 * 开发态：`npm run dev`（默认端口与商城错开，两个 dev server 可同时开）；
 * 生产态由 nginx 托管 `dist/` 并同源反代 `/api`（见仓库根 `frontend/nginx-admin.conf`）。
 */
const devTarget = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

const here = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@duoduo/api-sdk': path.resolve(here, '../../packages/api-sdk/src'),
      '@duoduo/shared': path.resolve(here, '../../packages/shared/src'),
    },
  },
  server: {
    // 5180：与商城的 5173 错开，便于同时开两个 dev server 对照排查
    port: 5180,
    proxy: {
      '/api': { target: devTarget, changeOrigin: true },
      '/sandbox': { target: devTarget, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      // 单入口：后台应用只产出 admin.html
      input: { admin: 'admin.html' },
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
});
