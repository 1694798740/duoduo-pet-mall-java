import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 第三方商家平台（E7-2：**独立应用**）。
 *
 * 与商城/后台的关系：源码、构建、产物三者都分开 —— 本应用入口只有 `merchant.html`。
 *
 * 为什么 `pages/merchant` 与 `pages/seller` 在**同一个应用里**：
 * `MerchantRoot` 的工作台路由直接引用 `../seller/*`（经营概览、商品、订单、售后、对账），
 * 二者是同一个「商家工作台」的两层（外壳 + 页面），拆开反而要额外绕一层包。它们同属商家侧，
 * 与商城/后台无任何交叉引用（已实测：页面之间不存在跨端引用）。
 *
 * 开发态：`npm run dev`（端口与商城错开）；生产态由 nginx 托管 `dist/`
 * 并同源反代 `/api`（见仓库根 `frontend/nginx-merchant.conf`）。
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
    // 5190：与商城 5173、后台 5180 都错开
    port: 5190,
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
      // 单入口：商家应用只产出 merchant.html
      input: { merchant: 'merchant.html' },
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
});
