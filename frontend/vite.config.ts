import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 开发态用代理把 /api 与 /sandbox 透传到网关（默认 :8080），
 * 这样浏览器始终是同源请求 ——
 *   · httpOnly Cookie（dd_at / dd_rt）可正常携带，不受 SameSite 限制
 *   · 不依赖后端 CORS 配置，前后端可以分别部署到不同端口/域名
 *
 * 生产态：VITE_API_BASE 留空即走同源；若前端与网关不同源，
 * 则设置 VITE_API_BASE=https://gateway.example.com 由后端 CORS 放行该来源
 * （后端白名单见 duoduo-gateway 的 CORS_ORIGINS）。
 */
const devTarget = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
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
      // 多入口（T3）：商城 index.html + 平台管理后台 admin.html。
      // 后台因此拥有独立产物与独立网址；商城产物不再需要承载后台入口。
      input: {
        main: 'index.html',
        admin: 'admin.html',
      },
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
});
