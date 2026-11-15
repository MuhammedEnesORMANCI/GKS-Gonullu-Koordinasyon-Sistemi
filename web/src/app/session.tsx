import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { sessionEvents, type SessionEndReason } from '../api/sessionEvents';
import { isApiError } from '../api/problem';
import { useServices } from '../services/registry';
import { markMockLoggedIn } from '../services/mock/services';
import type { MeContext, Role, Uuid } from '../services/types';
import { Q } from './queryKeys';

/**
 * Oturum durumu ve uygulama kabuğu (mapping §1.4, §4). Menü ve düğmeler `GET /me/context`'ten çizilir;
 * bu bir yetki kaynağı değildir, sunucu her istekte yeniden denetler.
 */

export type SessionStatus = 'loading' | 'authenticated' | 'anonymous' | 'unavailable';

export interface SessionValue {
  status: SessionStatus;
  context: MeContext | undefined;
  /** Son oturum sonu nedeni için kullanıcı mesajı (WEB-01'de gösterilir). */
  notice: string | null;
  clearNotice(): void;
  /** Başarılı girişten sonra çağrılır. */
  onLoggedIn(): Promise<void>;
  logout(): Promise<void>;
  retry(): void;
}

const SessionContext = createContext<SessionValue | null>(null);

const UNAUTHENTICATED_CODES = new Set(['AUTH_UNAUTHENTICATED', 'AUTH_SESSION_REVOKED', 'AUTH_TOKEN_EXPIRED']);

const END_MESSAGES: Record<SessionEndReason, string | null> = {
  AUTH_SESSION_REVOKED: 'Oturumunuz sonlandırıldı. Lütfen yeniden giriş yapın.',
  AUTH_UNAUTHENTICATED: 'Oturumunuz sonlandırıldı. Lütfen yeniden giriş yapın.',
  ACCOUNT_DISABLED: 'Hesabınız devre dışı. Kurum yöneticinizle iletişime geçin.',
  REFRESH_FAILED: 'Oturum süreniz doldu, yeniden giriş yapın.',
  LOGOUT: null,
};

export const NO_WEB_ACCESS_MESSAGE = 'Bu hesap web paneline erişemez; mobil uygulamayı kullanın.';

/** Bağlam herhangi bir web izni içeriyor mu? (LD-03 savunma kontrolü, mapping §1.2.) */
export function hasWebAccess(context: MeContext): boolean {
  const webRoles: Role[] = ['ADM', 'KOR', 'TEK', 'UYM'];
  return (
    context.roles.some((r) => webRoles.includes(r.role)) ||
    context.permissions.organization.length > 0 ||
    Object.values(context.permissions.operations).some((list) => list.length > 0)
  );
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const [notice, setNotice] = useState<string | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  /** Bu sekmede bağlam bir kez alındı mı? İlk açılıştaki 401 için "oturum sonlandı" mesajı gösterilmez. */
  const hadSession = useRef(false);

  const contextQuery = useQuery({
    queryKey: Q.meContext(),
    queryFn: () => services.meContext.getContext(),
    staleTime: 60_000,
    enabled: !anonymous,
    retry: (count, error) => isApiError(error) && error.isRetryable && count < 2,
  });

  // ID-01: uygulama açılışında CSRF çerezi (girişten önce WEB-01 de çağırır).
  useEffect(() => {
    services.auth.fetchCsrf().catch(() => undefined);
  }, [services]);

  const endSession = useCallback(
    (reason: SessionEndReason) => {
      // (1) SSE bu turda yok, (2) önbelleği tamamen temizle, (4) açık form taslakları bileşenlerle atılır, (5) WEB-01.
      queryClient.clear();
      markMockLoggedIn(false);
      setAnonymous(true);
      const silent = reason === 'AUTH_UNAUTHENTICATED' && !hadSession.current;
      hadSession.current = false;
      setNotice(silent ? null : END_MESSAGES[reason]);
      const from = location.pathname === '/giris' ? undefined : location.pathname + location.search;
      navigate('/giris', { replace: true, state: from ? { from } : undefined });
    },
    [queryClient, navigate, location.pathname, location.search],
  );

  useEffect(() => sessionEvents.sessionEnded.on(endSession), [endSession]);
  useEffect(
    () =>
      sessionEvents.forbidden.on(() => {
        void queryClient.invalidateQueries({ queryKey: Q.meContext() });
      }),
    [queryClient],
  );

  const logout = useCallback(async () => {
    try {
      await services.auth.logout();
    } catch {
      // İstek başarısız olsa da yerel temizlik yapılır (mapping §1.4).
    }
    sessionEvents.endSession('LOGOUT');
  }, [services]);

  const onLoggedIn = useCallback(async () => {
    markMockLoggedIn(true);
    setNotice(null);
    setAnonymous(false);
    await queryClient.invalidateQueries({ queryKey: Q.meContext() });
  }, [queryClient]);

  // Bağlam alınamadı: 401 → oturum yok; 503/ağ → oturum sonu DEĞİL (LD-48), yeniden denenebilir durum.
  const error = contextQuery.error;
  const unauthenticated = anonymous || (isApiError(error) && UNAUTHENTICATED_CODES.has(error.code));

  const ctx = contextQuery.data;
  useEffect(() => {
    if (ctx) hadSession.current = true;
  }, [ctx]);
  const refetchContext = contextQuery.refetch;

  // Savunma: web izni olmayan bağlam → mesaj + çıkış (LD-03).
  useEffect(() => {
    if (ctx && !hasWebAccess(ctx)) {
      void services.auth.logout().catch(() => undefined);
      queryClient.clear();
      markMockLoggedIn(false);
      setAnonymous(true);
      setNotice(NO_WEB_ACCESS_MESSAGE);
    }
  }, [ctx, services, queryClient]);

  let status: SessionStatus;
  if (unauthenticated) status = 'anonymous';
  else if (ctx) status = 'authenticated';
  else if (error) status = 'unavailable';
  else status = 'loading';

  const value = useMemo<SessionValue>(
    () => ({
      status,
      context: unauthenticated ? undefined : ctx,
      notice,
      clearNotice: () => setNotice(null),
      onLoggedIn,
      logout,
      retry: () => void refetchContext(),
    }),
    [status, unauthenticated, ctx, notice, onLoggedIn, logout, refetchContext],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('SessionProvider eksik');
  return value;
}

/** Rol ve izin yardımcıları (mapping §0.1, §4). */
export function usePermissions() {
  const { context } = useSession();
  return useMemo(() => {
    const roles = context?.roles ?? [];
    const has = (role: Role) => roles.some((r) => r.role === role);
    const isKorOf = (opId: Uuid) => roles.some((r) => r.role === 'KOR' && r.scopeId === opId);
    const can = (permission: string, opId?: Uuid) =>
      !!context &&
      (context.permissions.organization.includes(permission) ||
        (opId !== undefined && (context.permissions.operations[opId] ?? []).includes(permission)));
    return {
      isAdm: has('ADM'),
      isKor: has('KOR'),
      isTek: has('TEK'),
      isUym: has('UYM'),
      isKorOf,
      can,
      /**
       * ADM bu operasyonda yönetim izni taşımıyorsa salt-okur bandı. ADR-015 ile ADM tüm operasyonlarda
       * `task.manage` taşır; karar rol adından değil sunucunun etkin izinlerinden çıkar.
       */
      isAdmReadOnly: (opId: Uuid) => has('ADM') && !can('task.manage', opId),
    };
  }, [context]);
}
