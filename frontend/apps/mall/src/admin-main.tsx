import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { AdminRoot } from './pages/admin/AdminRoot';
import { ToastProvider } from '@duoduo/shared/components/ui';
import { AppProvider } from '@duoduo/shared/store/app';
import { AuthProvider } from '@duoduo/shared/store/auth';
import '@duoduo/shared/styles.css';

/**
 * 平台管理后台的第二个入口（T3）。
 *
 * 与 `src/main.tsx`（商城入口）共用同一套 Provider 与 API 层，但**只挂后台路由**：
 * 于是后台拥有了独立 HTML 产物（`dist/admin.html`）与独立网址，
 * 而商城产物里不再需要包含后台入口——这是向"三端分离"过渡的第一步。
 */
ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <AppProvider>
          <AuthProvider>
            <AdminRoot />
          </AuthProvider>
        </AppProvider>
      </ToastProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
