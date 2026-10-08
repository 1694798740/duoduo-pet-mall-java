import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { MerchantRoot } from './pages/merchant/MerchantRoot';
import { ToastProvider } from './components/ui';
import { AppProvider } from './store/app';
import { AuthProvider } from './store/auth';
import './styles.css';

/**
 * 第三方商家平台的第三个入口（E6）。
 *
 * 与商城入口（`src/main.tsx`）、平台后台入口（`src/admin-main.tsx`）共用同一套 Provider 与 API 层，
 * 但**只挂商家路由**：于是商家平台拥有了独立 HTML 产物（`dist/merchant.html`）与独立站点（8091）。
 */
ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <AppProvider>
          <AuthProvider>
            <MerchantRoot />
          </AuthProvider>
        </AppProvider>
      </ToastProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
