import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { ToastProvider } from '@duoduo/shared/components/ui';
import { ConfirmProvider } from '@duoduo/shared/components/confirm';
import { AppProvider } from '@duoduo/shared/store/app';
import { AuthProvider } from '@duoduo/shared/store/auth';
import '@duoduo/shared/styles.css';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <ConfirmProvider>
        <AppProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </AppProvider>
      </ConfirmProvider>
      </ToastProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
