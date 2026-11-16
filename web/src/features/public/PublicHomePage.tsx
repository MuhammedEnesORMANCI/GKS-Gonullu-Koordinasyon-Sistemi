import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardContent from '@mui/material/CardContent';
import Container from '@mui/material/Container';
import Grid from '@mui/material/Grid';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { Link as RouterLink } from 'react-router-dom';
import { BRAND } from '../../config/brand';
import { PALETTE } from '../../app/theme';
import { HAZARDS, SITE } from './content';
import { HazardIcon } from './icons';
import { PublicAnnouncementList } from './PublicAnnouncementList';
import { usePageMeta } from './usePageMeta';

/**
 * Hero zemini: üstte okunabilirlik için koyu katman, ortada değiştirilebilir görsel (`BRAND.heroImage`),
 * altta gradyan. Görsel yoksa yalnızca gradyan görünür.
 */
const HERO_BACKGROUND = [
  `linear-gradient(100deg, ${PALETTE.navyDark}F2 0%, ${PALETTE.navy}D9 55%, ${PALETTE.navyLight}B3 100%)`,
  `url("${BRAND.heroImage}") center / cover no-repeat`,
  `linear-gradient(135deg, ${PALETTE.navyDark} 0%, ${PALETTE.navy} 60%, ${PALETTE.navyLight} 100%)`,
].join(', ');

export function PublicHomePage() {
  usePageMeta(undefined, SITE.meta.description);
  return (
    <>
      <Box component="section" aria-labelledby="hero-baslik" sx={{ background: HERO_BACKGROUND, color: 'common.white', py: { xs: 7, md: 11 } }}>
        <Container maxWidth="lg">
          <Stack spacing={3} maxWidth={720}>
            <Box sx={{ width: 64, height: 5, bgcolor: PALETTE.orangeBright, borderRadius: 2 }} aria-hidden />
            <Typography id="hero-baslik" variant="h1" sx={{ fontSize: { xs: '2rem', md: '2.75rem' } }}>
              {SITE.hero.title}
            </Typography>
            <Typography sx={{ fontSize: '1.125rem', opacity: 0.95 }}>{SITE.hero.text}</Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Button component={RouterLink} to="/ne-yapmali" variant="contained" color="secondary" size="large">
                Afet Anında Ne Yapmalı?
              </Button>
              <Button
                component={RouterLink}
                to="/rehber"
                variant="outlined"
                size="large"
                sx={{ color: 'common.white', borderColor: 'common.white', '&:hover': { borderColor: 'common.white', bgcolor: 'rgba(255,255,255,0.1)' } }}
              >
                Hazırlık Rehberi
              </Button>
            </Stack>
          </Stack>
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: 6 }}>
        <Typography variant="h2" component="h2" gutterBottom sx={{ fontSize: { xs: '1.625rem', md: '2rem' } }}>
          {SITE.hazardsTitle}
        </Typography>
        <Grid container spacing={2.5} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
          {HAZARDS.map((h) => (
            <Grid item xs={12} sm={6} md={4} key={h.slug} component="li">
              <Card sx={{ height: '100%', transition: 'box-shadow .15s', '&:hover': { boxShadow: '0 6px 18px rgba(11,45,87,0.14)' } }}>
                <CardActionArea component={RouterLink} to={`/afet/${h.slug}`} sx={{ height: '100%' }}>
                  <CardContent sx={{ display: 'flex', gap: 2, alignItems: 'flex-start', p: 3 }}>
                    <Box
                      sx={{ bgcolor: PALETTE.orangeSoft, color: PALETTE.orange, borderRadius: 3, p: 1.5, display: 'flex' }}
                      aria-hidden
                    >
                      <HazardIcon name={h.icon} sx={{ fontSize: 44 }} />
                    </Box>
                    <Stack spacing={0.5}>
                      <Typography variant="h5" component="h3">
                        {h.title}
                      </Typography>
                      <Typography color="text.secondary">{h.summary}</Typography>
                    </Stack>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Container>

      <Container maxWidth="lg" sx={{ pb: 6 }}>
        <Grid container spacing={4}>
          <Grid item xs={12} md={5}>
            <Typography variant="h2" component="h2" gutterBottom sx={{ fontSize: { xs: '1.5rem', md: '1.75rem' } }}>
              {SITE.quickAccessTitle}
            </Typography>
            <Paper variant="outlined">
              <List disablePadding>
                {SITE.quickAccess.map((q) => (
                  <ListItemButton key={q.to} component={RouterLink} to={q.to} divider>
                    <ListItemText primary={q.label} primaryTypographyProps={{ fontWeight: 600 }} />
                    <ListItemIcon sx={{ minWidth: 0, color: 'secondary.main' }}>
                      <ChevronRightIcon />
                    </ListItemIcon>
                  </ListItemButton>
                ))}
              </List>
            </Paper>
          </Grid>
          <Grid item xs={12} md={7}>
            <Stack direction="row" justifyContent="space-between" alignItems="baseline">
              <Typography variant="h2" component="h2" gutterBottom sx={{ fontSize: { xs: '1.5rem', md: '1.75rem' } }}>
                {SITE.announcementsTitle}
              </Typography>
              <Button component={RouterLink} to="/duyurular">
                Tümü
              </Button>
            </Stack>
            <PublicAnnouncementList limit={3} compact />
          </Grid>
        </Grid>
      </Container>
    </>
  );
}
