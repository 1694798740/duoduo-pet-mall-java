import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { ToastProvider } from '@duoduo/shared/components/ui';
import { ConfirmProvider } from '@duoduo/shared/components/confirm';
import { AppProvider } from '@duoduo/shared/store/app';
import { SeckillProvider } from '@duoduo/shared/store/seckill';
import { AuthProvider } from '@duoduo/shared/store/auth';
import '@duoduo/shared/styles.css';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <ConfirmProvider>
        <AppProvider>
          {/* 全站秒杀信息只在这里拉一次：商品卡 / 详情页 / 首页栏目读同一份 */}
          <SeckillProvider>
            <AuthProvider>
              <App />
            </AuthProvider>
          </SeckillProvider>
        </AppProvider>
      </ConfirmProvider>
      </ToastProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
