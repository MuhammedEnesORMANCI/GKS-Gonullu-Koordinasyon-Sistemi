import { useState, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import { trTR } from '@mui/material/locale';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ServiceConfig } from '../config/services';
import type { Services } from '../services/contracts';
import { ServicesProvider } from '../services/registry';
import { LoginPage } from '../features/auth/LoginPage';
import { PasswordChangePage } from '../features/auth/PasswordChangePage';
import { InvitationsPage } from '../features/invitations/InvitationsPage';
import { HomePage } from '../features/dashboard/HomePage';
import { GroupDetailPage } from '../features/groups/GroupDetailPage';
import { GroupListPage } from '../features/groups/GroupListPage';
import { OperationDetailPage } from '../features/operations/OperationDetailPage';
import { OperationListPage } from '../features/operations/OperationListPage';
import { ProfilePage } from '../features/account/ProfilePage';
import { AnnouncementsPage } from '../features/notifications/AnnouncementsPage';
import { RoleAssignmentsPage } from '../features/roles/RoleAssignmentsPage';
import { UsersPage } from '../features/users/UsersPage';
import { AlertDetailPage, AlertListPage, OpenAlertsPage } from '../features/alerts/AlertPages';
import { AuditPage } from '../features/audit/AuditPage';
import { LocationPage } from '../features/location/LocationPage';
import { NotificationsPage } from '../features/notifications/NotificationsPage';
import { TaskDetailPage } from '../features/tasks/TaskDetailPage';
import { TaskListPage } from '../features/tasks/TaskListPage';
import { AppShell } from './AppShell';
import { createQueryClient } from './queryClient';
import { SessionProvider, useSession } from './session';

const theme = createTheme({ palette: { primary: { main: '#1d4f91' } } }, trTR);

/** Kimlik gerektiren rotalar: bağlam yüklenene kadar iskelet; 401 → WEB-01; 503 → oturum korunur (LD-48). */
function RequireSession({ children }: { children: ReactNode }) {
  const session = useSession();
  const location = useLocation();
  if (session.status === 'loading') {
    return (
      <Box display="flex" justifyContent="center" mt={10} aria-label="Yükleniyor">
        <CircularProgress />
      </Box>
    );
  }
  if (session.status === 'unavailable') {
    return (
      <Box maxWidth={520} mx="auto" mt={10}>
        <Alert
          severity="warning"
          action={
            <Button color="inherit" size="small" onClick={session.retry}>
              Tekrar dene
            </Button>
          }
        >
          Sunucuya şu an ulaşılamıyor. Biraz sonra yeniden denenecek.
        </Alert>
      </Box>
    );
  }
  if (session.status === 'anonymous') {
    return <Navigate to="/giris" replace state={{ from: location.pathname + location.search }} />;
  }
  // ID-08 kapısı: sunucu diğer uçları 403 AUTH_PASSWORD_CHANGE_REQUIRED ile reddeder (mapping §1.2).
  if (session.context?.session.mustChangePassword) {
    return <PasswordChangePage />;
  }
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/giris" element={<LoginPage />} />
      <Route
        element={
          <RequireSession>
            <AppShell />
          </RequireSession>
        }
      >
        <Route index element={<HomePage />} />
        <Route path="operasyonlar" element={<OperationListPage />} />
        <Route path="operasyonlar/:opId" element={<OperationDetailPage />} />
        <Route path="operasyonlar/:opId/gruplar" element={<GroupListPage />} />
        <Route path="operasyonlar/:opId/gruplar/:groupId" element={<GroupDetailPage />} />
        <Route path="operasyonlar/:opId/gorevler" element={<TaskListPage />} />
        <Route path="operasyonlar/:opId/gorevler/:taskId" element={<TaskDetailPage />} />
        <Route path="davetler" element={<InvitationsPage />} />
        <Route path="hesabim" element={<ProfilePage />} />
        <Route path="bildirimler" element={<NotificationsPage />} />
        <Route path="duyurular" element={<AnnouncementsPage />} />
        <Route path="roller" element={<RoleAssignmentsPage />} />
        <Route path="kullanicilar" element={<UsersPage />} />
        <Route path="denetim" element={<AuditPage />} />
        <Route path="alarmlar" element={<OpenAlertsPage />} />
        <Route path="alarmlar/:alertId" element={<AlertDetailPage />} />
        <Route path="operasyonlar/:opId/alarmlar" element={<AlertListPage />} />
        <Route path="operasyonlar/:opId/konum" element={<LocationPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

/** Sağlayıcılar; testler kendi yönlendiricisi ve servisleriyle `AppProviders` kullanır. */
export function AppProviders({
  children,
  queryClient,
  config,
  services,
}: {
  children: ReactNode;
  queryClient: QueryClient;
  config?: ServiceConfig;
  services?: Services;
}) {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <ServicesProvider config={config} services={services}>
          {children}
        </ServicesProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <AppProviders queryClient={queryClient}>
      <BrowserRouter>
        <SessionProvider>
          <AppRoutes />
        </SessionProvider>
      </BrowserRouter>
    </AppProviders>
  );
}
