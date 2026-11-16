import { useEffect, useState } from 'react';
import AppBar from '@mui/material/AppBar';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import LinearProgress from '@mui/material/LinearProgress';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import ListSubheader from '@mui/material/ListSubheader';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import MenuIcon from '@mui/icons-material/Menu';
import NotificationsIcon from '@mui/icons-material/Notifications';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { useIsFetching, useQuery } from '@tanstack/react-query';
import { Link as RouterLink, Outlet, matchPath, useLocation, useNavigate } from 'react-router-dom';
import { MockBadge } from '../components/MockBadge';
import { OPERATION_STATE_LABEL } from '../components/format';
import { useIsMock, useServices } from '../services/registry';
import { useLiveUpdates } from './live';
import { Q } from './queryKeys';
import { usePermissions, useSession } from './session';

const DRAWER_WIDTH = 260;
const UNREAD_POLL_MS = 30_000;
const SELECTED_OP_KEY = 'gks.selectedOperation';

function readSelectedOp(): string | undefined {
  try {
    return localStorage.getItem(SELECTED_OP_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

function writeSelectedOp(id: string) {
  try {
    localStorage.setItem(SELECTED_OP_KEY, id);
  } catch {
    // Kolaylıktır; doğruluk kaynağı URL'dir (mapping §0.2).
  }
}

/**
 * Uygulama kabuğu: üst çubuk (operasyon seçici, çıkış), role göre menü (web-flows §1.1).
 * Menü `me/context`'ten çizilir; WEB-02, 04 (davetler hariç), 07–12 bu turun kapsamı dışında olduğu için menüde yer almaz.
 */
export function AppShell() {
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up('md'));
  const [mobileOpen, setMobileOpen] = useState(false);
  const session = useSession();
  const perms = usePermissions();
  const services = useServices();
  const navigate = useNavigate();
  const location = useLocation();
  const fetching = useIsFetching();
  const contextMock = useIsMock('meContext');
  const listMock = useIsMock('operationList');

  const match = matchPath('/panel/operasyonlar/:opId/*', location.pathname);
  const urlOpId = match?.params.opId;
  const opId = urlOpId ?? readSelectedOp();

  useEffect(() => {
    if (urlOpId) writeSelectedOp(urlOpId);
  }, [urlOpId]);

  const canOperations = perms.isAdm || perms.isKor;
  const ctxPerms = session.context?.permissions;
  // Davet: kurum kapsamı (ADM) veya herhangi bir operasyonda invitation.create (MFA'lı KOR).
  const canInvite =
    !!ctxPerms &&
    (ctxPerms.organization.includes('invitation.create') ||
      Object.values(ctxPerms.operations).some((list) => list.includes('invitation.create')));

  // ADR-018: KOR kendi operasyonlarında, ADM tüm operasyonlarda.
  const canAlert = !!ctxPerms && Object.values(ctxPerms.operations).some((list) => list.includes('alert.view'));

  const canAnnounce =
    !!ctxPerms &&
    (ctxPerms.organization.includes('announcement.send.organization') ||
      Object.values(ctxPerms.operations).some((list) => list.includes('announcement.send.group')));

  // RT-01: gelen kutusu ve duyurular canlı yenilenir.
  useLiveUpdates();

  // Okunmamış bildirim sayısı (NT-08): akış olaylarıyla anında, kopuklukta yedek olarak düzenli yoklama.
  const unread = useQuery({
    queryKey: Q.unreadCount(),
    queryFn: () => services.notifications.unreadCount(),
    refetchInterval: UNREAD_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 10_000,
  });
  const unreadCount = unread.data ?? 0;

  // Seçici kaynağı OR-04 (mapping §0.2). TEK/UYM bu ucu kullanmaz.
  const operations = useQuery({
    queryKey: Q.operations({}),
    queryFn: () => services.operationList.list({}),
    enabled: canOperations,
    staleTime: 30_000,
  });

  const nav = [
    { label: 'Panel', to: '/panel', show: true },
    { label: 'Operasyonlar', to: '/panel/operasyonlar', show: canOperations },
    { label: 'Davetler', to: '/panel/davetler', show: canInvite },
    { label: 'Bildirimler', to: '/panel/bildirimler', show: true },
    { label: 'Duyurular', to: '/panel/duyurular', show: canAnnounce },
    { label: 'Acil alarmlar', to: '/panel/alarmlar', show: canAlert },
    { label: 'Roller', to: '/panel/roller', show: perms.can('role.assign') },
    { label: 'Kullanıcılar', to: '/panel/kullanicilar', show: perms.can('user.status.view') },
    { label: 'Denetim kayıtları', to: '/panel/denetim', show: perms.can('audit.view') },
    { label: 'Hesabım', to: '/panel/hesabim', show: true },
  ];
  const opNav = opId
    ? [
        { label: 'Operasyon detayı', to: `/panel/operasyonlar/${opId}`, show: canOperations },
        { label: 'Gruplar', to: `/panel/operasyonlar/${opId}/gruplar`, show: perms.isKorOf(opId) || perms.isAdm },
        { label: 'Görevler', to: `/panel/operasyonlar/${opId}/gorevler`, show: perms.isKorOf(opId) || perms.isAdm },
        { label: 'Acil alarmlar', to: `/panel/operasyonlar/${opId}/alarmlar`, show: perms.can('alert.view', opId) },
        { label: 'Son konumlar', to: `/panel/operasyonlar/${opId}/konum`, show: perms.can('location.view', opId) },
        { label: 'Operasyon davetleri', to: `/panel/davetler?kapsam=${opId}`, show: perms.can('invitation.create', opId) },
      ]
    : [];

  const isSelected = (to: string) => location.pathname === to || location.pathname.startsWith(`${to}/`);

  const drawer = (
    <Box role="navigation" aria-label="Ana menü">
      <Toolbar />
      <List>
        {nav
          .filter((n) => n.show)
          .map((n) => (
            <ListItemButton
              key={n.to}
              component={RouterLink}
              to={n.to}
              selected={n.to === '/' ? location.pathname === '/' : location.pathname === n.to}
              onClick={() => setMobileOpen(false)}
            >
              <ListItemText primary={n.label} />
            </ListItemButton>
          ))}
      </List>
      {opNav.some((n) => n.show) && (
        <>
          <Divider />
          <List subheader={<ListSubheader>Seçili operasyon</ListSubheader>}>
            {opNav
              .filter((n) => n.show)
              .map((n) => (
                <ListItemButton
                  key={n.to}
                  component={RouterLink}
                  to={n.to}
                  selected={n.to === `/panel/operasyonlar/${opId}` ? location.pathname === n.to : isSelected(n.to)}
                  onClick={() => setMobileOpen(false)}
                >
                  <ListItemText primary={n.label} />
                </ListItemButton>
              ))}
          </List>
        </>
      )}
      {contextMock && (
        <Box px={2} py={1}>
          <MockBadge detail="Menü ve düğmeler örnek GET /me/context yanıtına göre çiziliyor." />
        </Box>
      )}
    </Box>
  );

  const roleLabel = [
    perms.isAdm && 'ADM',
    perms.isKor && 'KOR',
    perms.isTek && 'TEK',
    perms.isUym && 'UYM',
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <Box display="flex">
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}>
        <Toolbar sx={{ gap: 2 }}>
          {!wide && (
            <IconButton color="inherit" edge="start" aria-label="Menüyü aç" onClick={() => setMobileOpen((v) => !v)}>
              <MenuIcon />
            </IconButton>
          )}
          <Typography variant="h6" noWrap sx={{ flexGrow: 1 }}>
            Gönüllü Koordinasyon
          </Typography>
          {canOperations && (
            <TextField
              select
              size="small"
              label="Aktif operasyon"
              value={operations.data?.items.some((o) => o.id === opId) ? opId : ''}
              onChange={(e) => {
                writeSelectedOp(e.target.value);
                navigate(`/panel/operasyonlar/${e.target.value}`);
              }}
              sx={{
                minWidth: 220,
                bgcolor: 'background.paper',
                borderRadius: 1,
              }}
              SelectProps={{ displayEmpty: true }}
              InputLabelProps={{ shrink: true }}
            >
              <MenuItem value="" disabled>
                Operasyon seçin
              </MenuItem>
              {(operations.data?.items ?? []).map((o) => (
                <MenuItem key={o.id} value={o.id}>
                  {o.name} — {OPERATION_STATE_LABEL[o.state]}
                </MenuItem>
              ))}
            </TextField>
          )}
          {canOperations && listMock && <MockBadge detail="Operasyon listesi (GET /operations) örnek veri." />}
          <IconButton
            color="inherit"
            component={RouterLink}
            to="/panel/bildirimler"
            aria-label={unreadCount > 0 ? `Bildirimler, ${unreadCount} okunmamış` : 'Bildirimler'}
          >
            <Badge badgeContent={unreadCount} color="error" max={99}>
              <NotificationsIcon />
            </Badge>
          </IconButton>
          <Typography variant="body2" sx={{ display: { xs: 'none', sm: 'block' } }}>
            {session.context?.user.displayName}
            {roleLabel && ` (${roleLabel})`}
          </Typography>
          <Button color="inherit" onClick={() => void session.logout()}>
            Çıkış
          </Button>
        </Toolbar>
        {fetching > 0 && <LinearProgress color="secondary" sx={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: 2 }} />}
      </AppBar>
      <Drawer
        variant={wide ? 'permanent' : 'temporary'}
        open={wide || mobileOpen}
        onClose={() => setMobileOpen(false)}
        sx={{ width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: 3, minWidth: 0 }}>
        <Toolbar />
        <Outlet />
      </Box>
    </Box>
  );
}
