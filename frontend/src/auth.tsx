import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode
} from 'react';
import { api, configureApi, setApiAccessToken, TOKEN_KEY } from './api';
import { useI18n } from './i18n';
import type { AuthResponse, AuthUser } from './types';

type AuthStatus = 'restoring' | 'authenticated' | 'anonymous';

type AuthValue = {
  token: string;
  user: AuthUser | null;
  status: AuthStatus;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthValue | null>(null);
const CHANNEL_NAME = 'inventory-auth';

export function canViewPrices(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'CASHIER';
}

export function canCreateOrders(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'CASHIER';
}

export function canEditDraftItems(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'CASHIER';
}

export function canViewAllOrders(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'AUDITOR';
}

export function canViewDashboard(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'AUDITOR';
}

export function canViewStockHistory(user: { role: string } | null | undefined) {
  return user?.role === 'ADMIN' || user?.role === 'AUDITOR';
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState('');
  const [user, setUser] = useState<AuthUser | null>(null);
  const [status, setStatus] = useState<AuthStatus>('restoring');
  const refreshRef = useRef<Promise<string> | null>(null);
  const refreshActionRef = useRef<() => Promise<string>>(async () => '');
  const expiryTimer = useRef<number | null>(null);
  const channelRef = useRef<BroadcastChannel | null>(null);
  const { locale } = useI18n();

  const clear = useCallback(() => {
    setApiAccessToken('');
    setToken('');
    setUser(null);
    setStatus('anonymous');
    if (expiryTimer.current !== null) window.clearTimeout(expiryTimer.current);
  }, []);

  const apply = useCallback((response: AuthResponse) => {
    setApiAccessToken(response.accessToken);
    setToken(response.accessToken);
    setUser(response.user);
    setStatus('authenticated');
    if (expiryTimer.current !== null) window.clearTimeout(expiryTimer.current);
    const expiresIn = Number(response.expiresIn);
    if (Number.isFinite(expiresIn)) {
      expiryTimer.current = window.setTimeout(
        () => { void refreshActionRef.current(); },
        Math.max(5_000, (expiresIn - 60) * 1000)
      );
    }
  }, []);

  const runWithCrossTabLock = useCallback(async <T,>(operation: () => Promise<T>): Promise<T> => {
    const locks = (navigator as Navigator & {
      locks?: { request: <R>(name: string, callback: () => Promise<R>) => Promise<R> }
    }).locks;
    return locks ? locks.request('inventory-auth-refresh', operation) : operation();
  }, []);

  const refresh = useCallback(async () => {
    if (refreshRef.current) return refreshRef.current;
    const pending = runWithCrossTabLock(async () => {
      try {
        const response = await api.refreshSession();
        apply(response);
        return response.accessToken;
      } catch {
        clear();
        return '';
      }
    }).finally(() => { refreshRef.current = null; });
    refreshRef.current = pending;
    return pending;
  }, [apply, clear, runWithCrossTabLock]);
  refreshActionRef.current = refresh;

  configureApi({ token, locale, unauthorized: clear, refresh });

  useEffect(() => {
    localStorage.removeItem(TOKEN_KEY);
    channelRef.current = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL_NAME);
    if (channelRef.current) {
      channelRef.current.onmessage = event => {
        if (event.data === 'logout') clear();
        if (event.data === 'login') void refresh();
      };
    }
    void refresh();
    return () => {
      channelRef.current?.close();
      if (expiryTimer.current !== null) window.clearTimeout(expiryTimer.current);
    };
  }, [clear, refresh]);

  const login = useCallback(async (username: string, password: string) => {
    const response = await api.login(username, password);
    apply(response);
    channelRef.current?.postMessage('login');
  }, [apply]);

  const logout = useCallback(async () => {
    try { await api.logout(); } finally {
      clear();
      channelRef.current?.postMessage('logout');
    }
  }, [clear]);

  const value = useMemo(
    () => ({ token, user, status, login, logout }),
    [token, user, status, login, logout]
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
