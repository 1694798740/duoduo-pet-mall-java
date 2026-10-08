import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 开发态用代理把 /api 与 /sandbox 透传到网关（默认 :8080），
 * 这样浏览器始终是同源请求 ——
 *   · httpOnly Cookie（dd_at / dd_rt）可正常携带，不受 SameSite 限制
 *   · 不依赖后端 CORS 配置，前后端可以分别部署到不同端口/域名
 *
 * 生产态：VITE_API_BASE 留空即走同源；若前端与网关不同源，
 * 则设置 VITE_API_BASE=https://gateway.example.com 由后端 CORS 放行该来源
 * （后端白名单见 duoduo-gateway 的 CORS_ORIGINS）。
 *
 * E7-2 起本应用只管**商城**：入口只有 `index.html`，产物只有自己的 `dist/`。
 * 后台与商家已各自独立成 `apps/admin`、`apps/merchant`（各自构建、各自镜像）。
 */
const devTarget = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * E7（monorepo）：共用包**不预构建**，直接吃 TS 源码 ——
 * 少一道「先 build 包再 build 应用」的步骤，也就少一类「改了 packages 忘了重编」的假通过。
 * 别名指向各包的 `src` 目录，所以 `@duoduo/shared/components/ui` 会解析到
 * `packages/shared/src/components/ui.tsx`（Vite 自动补扩展名）。
 *
 * 注意：字符串别名按**前缀**匹配 —— `@duoduo/api-sdk/endpoints` 命中 `@duoduo/api-sdk`
 * 前缀后拼成 `<packages/api-sdk/src>/endpoints`，因此带子路径与不带子路径两种写法都可用。
 * （写这段注释时踩过一次坑：路径通配写法里含有 `星号+斜杠`，会把块注释提前闭合 —— 见下方 alias 注释。）
 */
const PKG_API_SDK = path.resolve(here, '../../packages/api-sdk/src');
const PKG_SHARED = path.resolve(here, '../../packages/shared/src');

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@duoduo/api-sdk': PKG_API_SDK,
      '@duoduo/shared': PKG_SHARED,
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: devTarget, changeOrigin: true },
      // 沙箱收银台是服务端渲染页面，二维码指向网关的 /sandbox/pay/**
      '/sandbox': { target: devTarget, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      // 单入口（E7-2）：商城应用只产出 index.html。
      // 后台与商家在 E4–E6 曾作为本应用的另外两个入口（多入口过渡方案），
      // E7-2 已各自迁成独立应用（apps/admin、apps/merchant）—— 商城产物里不再有它们的任何痕迹。
      input: { main: 'index.html' },
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
});
