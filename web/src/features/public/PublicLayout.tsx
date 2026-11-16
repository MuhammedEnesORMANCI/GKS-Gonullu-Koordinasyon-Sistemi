import { useState } from 'react';
import AppBar from '@mui/material/AppBar';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Container from '@mui/material/Container';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import MenuIcon from '@mui/icons-material/Menu';
import PhoneInTalkIcon from '@mui/icons-material/PhoneInTalk';
import { Link as RouterLink, NavLink, Outlet } from 'react-router-dom';
import { BrandLogo } from '../../components/brand/BrandLogo';
import { BRAND } from '../../config/brand';
import { PALETTE } from '../../app/theme';
import { useSession } from '../../app/session';
import { SITE } from './content';

export const PUBLIC_NAV = [
  { label: 'Afet Bilgisi', to: '/afet/deprem' },
  { label: 'Hazırlık Rehberi', to: '/rehber' },
  { label: 'Duyurular', to: '/duyurular' },
  { label: 'Gönüllülük', to: '/gonulluluk' },
  { label: 'İletişim', to: '/iletisim' },
] as const;

/**
 * Halka açık site düzeni: üst bilgi (logo + kurum metni, menü, Giriş/Panele git), içerik, alt bilgi.
 * Oturum gerektirmez; iç veri (operasyon, kişi, konum, iç duyuru) göstermez.
 */
export function PublicLayout() {
  const session = useSession();
  const [open, setOpen] = useState(false);
  const loggedIn = session.status === 'authenticated';
  const account = loggedIn ? (
    <Button component={RouterLink} to="/panel" variant="contained" color="secondary">
      Panele git
    </Button>
  ) : (
    <Button component={RouterLink} to="/giris" variant="contained" color="secondary">
      Giriş
    </Button>
  );

  return (
    <Box minHeight="100vh" display="flex" flexDirection="column" bgcolor="background.default">
      <Link
        href="#icerik"
        sx={{
          position: 'absolute',
          left: -9999,
          '&:focus': { left: 16, top: 8, zIndex: 2000, bgcolor: 'common.white', p: 1, borderRadius: 1 },
        }}
      >
        İçeriğe geç
      </Link>
      <Box bgcolor={PALETTE.navyDark} color="common.white" py={0.5}>
        <Container maxWidth="lg" sx={{ display: 'flex', alignItems: 'center', gap: 1, justifyContent: 'flex-end' }}>
          <PhoneInTalkIcon fontSize="small" aria-hidden />
          <Typography variant="body2">
            Acil durumda <strong>{BRAND.emergencyNumber}</strong>
          </Typography>
        </Container>
      </Box>
      <AppBar position="sticky" color="inherit" sx={{ bgcolor: 'common.white', borderBottom: `3px solid ${PALETTE.orangeBright}` }}>
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ gap: 2, minHeight: 76 }}>
            <Box
              component={RouterLink}
              to="/"
              aria-label="Ana sayfa"
              sx={{ display: 'flex', alignItems: 'center', gap: 1.5, textDecoration: 'none', color: 'inherit', minWidth: 0 }}
            >
              <BrandLogo height={44} />
              <Typography
                sx={{ display: { xs: 'none', sm: 'block' }, fontWeight: 700, color: 'primary.main', lineHeight: 1.2, maxWidth: 300, fontSize: '0.9375rem' }}
              >
                {BRAND.organization}
              </Typography>
            </Box>
            <Box flexGrow={1} />
            <Box component="nav" aria-label="Site menüsü" sx={{ display: { xs: 'none', lg: 'flex' }, gap: 0.5 }}>
              {PUBLIC_NAV.map((n) => (
                <Button
                  key={n.to}
                  component={NavLink}
                  to={n.to}
                  sx={{ color: 'primary.main', '&.active': { color: 'secondary.main', boxShadow: `inset 0 -3px 0 ${PALETTE.orange}` } }}
                >
                  {n.label}
                </Button>
              ))}
            </Box>
            <Box sx={{ display: { xs: 'none', sm: 'block' } }}>{account}</Box>
            <IconButton aria-label="Menüyü aç" onClick={() => setOpen(true)} sx={{ display: { lg: 'none' }, color: 'primary.main' }}>
              <MenuIcon />
            </IconButton>
          </Toolbar>
        </Container>
      </AppBar>
      <Drawer anchor="right" open={open} onClose={() => setOpen(false)}>
        <Box width={280} role="navigation" aria-label="Site menüsü (mobil)" p={2}>
          <List>
            {PUBLIC_NAV.map((n) => (
              <ListItemButton key={n.to} component={RouterLink} to={n.to} onClick={() => setOpen(false)}>
                <ListItemText primary={n.label} />
              </ListItemButton>
            ))}
          </List>
          <Divider sx={{ my: 1 }} />
          <Box onClick={() => setOpen(false)}>{account}</Box>
        </Box>
      </Drawer>
      <Box component="main" id="icerik" flexGrow={1} tabIndex={-1} sx={{ outline: 'none' }}>
        <Outlet />
      </Box>
      <Box component="footer" bgcolor={PALETTE.navy} color="common.white" mt={6} pt={5} pb={3}>
        <Container maxWidth="lg">
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={4} justifyContent="space-between">
            <Stack spacing={1.5} maxWidth={420}>
              <BrandLogo height={40} inverted />
              <Typography fontWeight={700}>{BRAND.organization}</Typography>
              <Typography variant="body2" sx={{ opacity: 0.92 }}>
                {SITE.footer.text}
              </Typography>
            </Stack>
            <Stack component="nav" aria-label="Alt menü" spacing={1}>
              <Link component={RouterLink} to="/" color="inherit" underline="hover">
                Ana Sayfa
              </Link>
              <Link component={RouterLink} to="/ne-yapmali" color="inherit" underline="hover">
                Afet Anında Ne Yapmalı?
              </Link>
              {PUBLIC_NAV.map((n) => (
                <Link key={n.to} component={RouterLink} to={n.to} color="inherit" underline="hover">
                  {n.label}
                </Link>
              ))}
            </Stack>
            <Stack spacing={1}>
              <Typography fontWeight={700}>Acil Çağrı</Typography>
              <Typography variant="h3" component="p" sx={{ color: 'common.white' }}>
                {BRAND.emergencyNumber}
              </Typography>
            </Stack>
          </Stack>
          <Divider sx={{ borderColor: 'rgba(255,255,255,0.2)', my: 3 }} />
          <Typography variant="caption" sx={{ opacity: 0.9 }}>
            © {BRAND.shortName} · {BRAND.systemName}
          </Typography>
        </Container>
      </Box>
    </Box>
  );
}
