import { useEffect, useState, type ReactElement } from 'react';
import AppBar from '@mui/material/AppBar';
import Avatar from '@mui/material/Avatar';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import LinearProgress from '@mui/material/LinearProgress';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import ListSubheader from '@mui/material/ListSubheader';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import MapIcon from '@mui/icons-material/Map';
import AssignmentIcon from '@mui/icons-material/Assignment';
import CampaignIcon from '@mui/icons-material/Campaign';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import CrisisAlertIcon from '@mui/icons-material/CrisisAlert';
import DashboardIcon from '@mui/icons-material/Dashboard';
import ForumIcon from '@mui/icons-material/Forum';
import GroupsIcon from '@mui/icons-material/Groups';
import HistoryEduIcon from '@mui/icons-material/HistoryEdu';
import InfoIcon from '@mui/icons-material/Info';
import LogoutIcon from '@mui/icons-material/Logout';
import MailIcon from '@mui/icons-material/Mail';
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts';
import MenuIcon from '@mui/icons-material/Menu';
import NotificationsIcon from '@mui/icons-material/Notifications';
import PeopleIcon from '@mui/icons-material/People';
import PlaceIcon from '@mui/icons-material/Place';
import PublicIcon from '@mui/icons-material/Public';
import RecordVoiceOverIcon from '@mui/icons-material/RecordVoiceOver';
import WorkIcon from '@mui/icons-material/Work';
import { useIsFetching, useQuery } from '@tanstack/react-query';
import { Link as RouterLink, Outlet, matchPath, useLocation, useNavigate } from 'react-router-dom';
import { BrandLogo } from '../components/brand/BrandLogo';
import { MockBadge } from '../components/MockBadge';
import { OPERATION_STATE_LABEL } from '../components/format';
import { BRAND } from '../config/brand';
import { useIsMock, useServices } from '../services/registry';
import { useLiveUpdates } from './live';
import { Q } from './queryKeys';
import { usePermissions, useSession } from './session';
import { PALETTE } from './theme';

const DRAWER_WIDTH = 272;
const DRAWER_COLLAPSED = 76;
const UNREAD_POLL_MS = 30_000;
const SELECTED_OP_KEY = 'gks.selectedOperation';
const COLLAPSED_KEY = 'gks.sidebarCollapsed';

function readStored(key: string): string | undefined {
  try {
    return localStorage.getItem(key) ?? undefined;
  } catch {
    return undefined;
  }
}

function writeStored(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Kolaylıktır; doğruluk kaynağı URL'dir (mapping §0.2).
  }
}

export interface PanelNavItem {
  label: string;
  to: string;
  icon: ReactElement;
  show: boolean;
  /** Tam eşleşme (alt sayfalarda seçili görünmez). */
  exact?: boolean;
}

export interface PanelNavGroup {
  title?: string;
  items: PanelNavItem[];
}

/** Sayfa başlığı (üst çubuk): ilk eşleşen kalıp. */
const PAGE_TITLES: [string, string][] = [
  ['/panel', 'Özet'],
  ['/panel/operasyonlar', 'Operasyonlar'],
  ['/panel/operasyonlar/:opId', 'Operasyon detayı'],
  ['/panel/operasyonlar/:opId/gruplar/*', 'Gruplar'],
  ['/panel/operasyonlar/:opId/gorevler/*', 'Görevler'],
  ['/panel/operasyonlar/:opId/alarmlar', 'Acil Alarmlar'],
  ['/panel/operasyonlar/:opId/konum', 'Konumlar'],
  ['/panel/alarmlar/*', 'Acil Alarmlar'],
  ['/panel/duyurular', 'Duyurular'],
  ['/panel/kamu-duyurulari', 'Kamu Duyuruları'],
  ['/panel/davetler', 'Davetler'],
  ['/panel/bildirimler', 'Bildirimler'],
  ['/panel/kullanicilar', 'Kullanıcılar'],
  ['/panel/roller', 'Roller'],
  ['/panel/denetim', 'Denetim Kayıtları'],
  ['/panel/hesabim', 'Hesabım'],
  ['/panel/mesajlar/*', 'Mesajlar'],
  ['/panel/harita', 'Operasyon Haritası'],
];

export function panelPageTitle(pathname: string): string {
  return PAGE_TITLES.find(([pattern]) => matchPath(pattern, pathname))?.[1] ?? BRAND.systemName;
}

/** Menü (izinlere göre). Sunucu her istekte yeniden denetler; görünürlük yalnızca kolaylıktır. */
export function usePanelNav(opId: string | undefined): PanelNavGroup[] {
  const session = useSession();
  const perms = usePermissions();
  const ctx = session.context?.permissions;
  const anyOp = (permission: string) => !!ctx && Object.values(ctx.operations).some((list) => list.includes(permission));
  const canOperations = perms.isAdm || perms.isKor;
  const canInvite = !!ctx && (ctx.organization.includes('invitation.create') || anyOp('invitation.create'));
  const canAlert = anyOp('alert.view');
  const canAnnounce = !!ctx && (ctx.organization.includes('announcement.send.organization') || anyOp('announcement.send.group'));
  const manageOp = !!opId && (perms.isKorOf(opId) || perms.isAdm);
  return [
    {
      items: [
        { label: 'Özet', to: '/panel', icon: <DashboardIcon />, show: true, exact: true },
        { label: 'Operasyonlar', to: '/panel/operasyonlar', icon: <WorkIcon />, show: canOperations, exact: true },
        { label: 'Operasyon Haritası', to: '/panel/harita', icon: <MapIcon />, show: canOperations },
        { label: 'Acil Alarmlar', to: '/panel/alarmlar', icon: <CrisisAlertIcon />, show: canAlert },
        { label: 'Mesajlar', to: '/panel/mesajlar', icon: <ForumIcon />, show: canOperations },
        { label: 'Bildirimler', to: '/panel/bildirimler', icon: <NotificationsIcon />, show: true },
      ],
    },
    {
      title: 'İletişim',
      items: [
        { label: 'Duyurular', to: '/panel/duyurular', icon: <RecordVoiceOverIcon />, show: canAnnounce },
        { label: 'Kamu Duyuruları', to: '/panel/kamu-duyurulari', icon: <CampaignIcon />, show: perms.can('public_announcement.manage') },
        { label: 'Davetler', to: '/panel/davetler', icon: <MailIcon />, show: canInvite, exact: true },
      ],
    },
    {
      title: 'Seçili operasyon',
      items: opId
        ? [
            { label: 'Operasyon detayı', to: `/panel/operasyonlar/${opId}`, icon: <InfoIcon />, show: canOperations, exact: true },
            { label: 'Görevler', to: `/panel/operasyonlar/${opId}/gorevler`, icon: <AssignmentIcon />, show: manageOp },
            { label: 'Gruplar', to: `/panel/operasyonlar/${opId}/gruplar`, icon: <GroupsIcon />, show: manageOp },
            { label: 'Operasyon alarmları', to: `/panel/operasyonlar/${opId}/alarmlar`, icon: <CrisisAlertIcon />, show: perms.can('alert.view', opId) },
            { label: 'Konumlar', to: `/panel/operasyonlar/${opId}/konum`, icon: <PlaceIcon />, show: perms.can('location.view', opId) },
            { label: 'Operasyon davetleri', to: `/panel/davetler?kapsam=${opId}`, icon: <MailIcon />, show: perms.can('invitation.create', opId) },
          ]
        : [],
    },
    {
      title: 'Yönetim',
      items: [
        { label: 'Kullanıcılar', to: '/panel/kullanicilar', icon: <PeopleIcon />, show: perms.can('user.status.view') },
        { label: 'Roller', to: '/panel/roller', icon: <ManageAccountsIcon />, show: perms.can('role.assign') },
        { label: 'Denetim Kayıtları', to: '/panel/denetim', icon: <HistoryEduIcon />, show: perms.can('audit.view') },
      ],
    },
    {
      items: [{ label: 'Hesabım', to: '/panel/hesabim', icon: <AccountCircleIcon />, show: true }],
    },
  ];
}

/**
 * Yönetim paneli kabuğu (ADR-023): solda daraltılabilir yan menü (mobilde çekmece), üstte başlık çubuğu (sayfa
 * başlığı, aktif operasyon seçici, okunmamış bildirim sayacı, kullanıcı menüsü/çıkış). Menü `me/context` izinlerinden.
 */
export function AppShell() {
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up('md'));
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(readStored(COLLAPSED_KEY) === '1');
  const [userMenu, setUserMenu] = useState<HTMLElement | null>(null);
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
  const opId = urlOpId ?? readStored(SELECTED_OP_KEY);

  useEffect(() => {
    if (urlOpId) writeStored(SELECTED_OP_KEY, urlOpId);
  }, [urlOpId]);

  const canOperations = perms.isAdm || perms.isKor;
  const groups = usePanelNav(opId);

  // RT-01: gelen kutusu, alarmlar, duyurular canlı yenilenir.
  useLiveUpdates();

  const unread = useQuery({
    queryKey: Q.unreadCount(),
    queryFn: () => services.notifications.unreadCount(),
    refetchInterval: UNREAD_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 10_000,
  });
  const unreadCount = unread.data ?? 0;

  const operations = useQuery({
    queryKey: Q.operations({}),
    queryFn: () => services.operationList.list({}),
    enabled: canOperations,
    staleTime: 30_000,
  });

  const mini = wide && collapsed;
  const width = mini ? DRAWER_COLLAPSED : DRAWER_WIDTH;
  const isSelected = (item: PanelNavItem) => {
    const path = item.to.split('?')[0]!;
    return item.exact ? location.pathname === path : location.pathname === path || location.pathname.startsWith(`${path}/`);
  };
  const title = panelPageTitle(location.pathname);
  const roleLabel = [perms.isAdm && 'ADM', perms.isKor && 'KOR', perms.isTek && 'TEK', perms.isUym && 'UYM'].filter(Boolean).join(', ');
  const displayName = session.context?.user.displayName ?? '';

  const drawer = (
    <Box role="navigation" aria-label="Ana menü" sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Toolbar sx={{ gap: 1.5, px: mini ? 1.5 : 2.5, minHeight: 72 }}>
        <Box component={RouterLink} to="/panel" aria-label="Özet" sx={{ display: 'flex', alignItems: 'center', gap: 1.25, textDecoration: 'none' }}>
          <BrandLogo height={mini ? 30 : 38} inverted />
          {!mini && (
            <Typography sx={{ color: 'common.white', fontWeight: 800, lineHeight: 1.15, fontSize: '0.9375rem' }}>
              {BRAND.systemName}
            </Typography>
          )}
        </Box>
      </Toolbar>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.14)' }} />
      <Box sx={{ flexGrow: 1, overflowY: 'auto', py: 1 }}>
        {groups.map((group, gi) => {
          const items = group.items.filter((i) => i.show);
          if (items.length === 0) return null;
          return (
            <List
              key={group.title ?? gi}
              dense={false}
              subheader={
                group.title && !mini ? (
                  <ListSubheader disableSticky sx={{ bgcolor: 'transparent', color: 'rgba(255,255,255,0.72)', lineHeight: '32px', fontWeight: 700 }}>
                    {group.title}
                  </ListSubheader>
                ) : undefined
              }
              sx={{ px: 1 }}
            >
              {items.map((item) => {
                const button = (
                  <ListItemButton
                    key={item.to}
                    component={RouterLink}
                    to={item.to}
                    selected={isSelected(item)}
                    aria-current={isSelected(item) ? 'page' : undefined}
                    onClick={() => setMobileOpen(false)}
                    sx={{
                      color: 'rgba(255,255,255,0.92)',
                      mb: 0.25,
                      justifyContent: mini ? 'center' : 'flex-start',
                      '&.Mui-selected': { bgcolor: PALETTE.orange, color: 'common.white', '&:hover': { bgcolor: PALETTE.orange } },
                      '&:hover': { bgcolor: 'rgba(255,255,255,0.08)' },
                    }}
                  >
                    <ListItemIcon sx={{ color: 'inherit', minWidth: mini ? 0 : 40 }}>{item.icon}</ListItemIcon>
                    {mini ? <Box component="span" sx={visuallyHidden}>{item.label}</Box> : <ListItemText primary={item.label} />}
                  </ListItemButton>
                );
                return mini ? (
                  <Tooltip key={item.to} title={item.label} placement="right">
                    {button}
                  </Tooltip>
                ) : (
                  button
                );
              })}
            </List>
          );
        })}
      </Box>
      {contextMock && !mini && (
        <Box px={2} py={1}>
          <MockBadge detail="Menü ve düğmeler örnek GET /me/context yanıtına göre çiziliyor." />
        </Box>
      )}
      {wide && (
        <>
          <Divider sx={{ borderColor: 'rgba(255,255,255,0.14)' }} />
          <Box p={1} display="flex" justifyContent={mini ? 'center' : 'flex-end'}>
            <IconButton
              aria-label={collapsed ? 'Menüyü genişlet' : 'Menüyü daralt'}
              onClick={() => {
                setCollapsed((v) => !v);
                writeStored(COLLAPSED_KEY, collapsed ? '0' : '1');
              }}
              sx={{ color: 'common.white' }}
            >
              {collapsed ? <ChevronRightIcon /> : <ChevronLeftIcon />}
            </IconButton>
          </Box>
        </>
      )}
    </Box>
  );

  return (
    <Box display="flex" minHeight="100vh" bgcolor="background.default">
      <AppBar
        position="fixed"
        color="inherit"
        sx={{
          bgcolor: 'common.white',
          borderBottom: `1px solid ${PALETTE.border}`,
          width: { md: `calc(100% - ${width}px)` },
          ml: { md: `${width}px` },
          transition: 'width .2s, margin .2s',
        }}
      >
        <Toolbar sx={{ gap: 2, minHeight: 72 }}>
          {!wide && (
            <IconButton edge="start" aria-label="Menüyü aç" onClick={() => setMobileOpen((v) => !v)} sx={{ color: 'primary.main' }}>
              <MenuIcon />
            </IconButton>
          )}
          <Typography variant="h6" component="p" noWrap sx={{ flexGrow: 1, color: 'primary.main', fontWeight: 800 }}>
            {title}
          </Typography>
          {canOperations && (
            <TextField
              select
              size="small"
              label="Aktif operasyon"
              value={operations.data?.items.some((o) => o.id === opId) ? opId : ''}
              onChange={(e) => {
                writeStored(SELECTED_OP_KEY, e.target.value);
                navigate(`/panel/operasyonlar/${e.target.value}`);
              }}
              sx={{ minWidth: { xs: 140, sm: 240 }, display: { xs: 'none', sm: 'inline-flex' } }}
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
            component={RouterLink}
            to="/panel/bildirimler"
            aria-label={unreadCount > 0 ? `Bildirimler, ${unreadCount} okunmamış` : 'Bildirimler'}
            sx={{ color: 'primary.main' }}
          >
            <Badge badgeContent={unreadCount} color="error" max={99}>
              <NotificationsIcon />
            </Badge>
          </IconButton>
          <IconButton aria-label="Kullanıcı menüsü" aria-haspopup="menu" onClick={(e) => setUserMenu(e.currentTarget)}>
            <Avatar sx={{ bgcolor: PALETTE.navy, width: 36, height: 36, fontSize: '1rem' }}>{displayName.slice(0, 1).toLocaleUpperCase('tr')}</Avatar>
          </IconButton>
          <Menu anchorEl={userMenu} open={!!userMenu} onClose={() => setUserMenu(null)} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }} transformOrigin={{ vertical: 'top', horizontal: 'right' }}>
            <Box px={2} py={1}>
              <Typography fontWeight={700}>{displayName}</Typography>
              {roleLabel && (
                <Typography variant="caption" color="text.secondary">
                  {roleLabel}
                </Typography>
              )}
            </Box>
            <Divider />
            <MenuItem component={RouterLink} to="/panel/hesabim" onClick={() => setUserMenu(null)}>
              <ListItemIcon>
                <AccountCircleIcon fontSize="small" />
              </ListItemIcon>
              Hesabım
            </MenuItem>
            <MenuItem component={RouterLink} to="/" onClick={() => setUserMenu(null)}>
              <ListItemIcon>
                <PublicIcon fontSize="small" />
              </ListItemIcon>
              Halka açık site
            </MenuItem>
            <MenuItem
              onClick={() => {
                setUserMenu(null);
                void session.logout();
              }}
            >
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              Çıkış
            </MenuItem>
          </Menu>
        </Toolbar>
        {fetching > 0 && <LinearProgress color="secondary" sx={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: 2 }} />}
      </AppBar>
      <Drawer
        variant={wide ? 'permanent' : 'temporary'}
        open={wide || mobileOpen}
        onClose={() => setMobileOpen(false)}
        PaperProps={{ sx: { width, bgcolor: PALETTE.navy, color: 'common.white', borderRight: 0, transition: 'width .2s', overflowX: 'hidden' } }}
        sx={{ width, flexShrink: 0, transition: 'width .2s' }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, minWidth: 0 }}>
        <Toolbar sx={{ minHeight: 72 }} />
        <Outlet />
      </Box>
    </Box>
  );
}

const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const;
