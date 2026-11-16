import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Container from '@mui/material/Container';
import Grid from '@mui/material/Grid';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { PALETTE } from '../../app/theme';
import { GUIDE, HAZARDS, findHazard } from './content';
import { GuideIcon, HazardIcon } from './icons';
import { PageHero } from './PageHero';
import { PublicNotFoundPage } from './PublicNotFoundPage';
import { usePageMeta } from './usePageMeta';

const PHASES = [
  { key: 'before', title: 'Öncesinde', color: PALETTE.navyLight },
  { key: 'during', title: 'Sırasında', color: PALETTE.orange },
  { key: 'after', title: 'Sonrasında', color: PALETTE.success },
] as const;

/** `/afet/:tur`: afet türüne göre öncesinde / sırasında / sonrasında yapılacaklar. */
export function HazardPage() {
  const { tur } = useParams();
  const hazard = findHazard(tur);
  usePageMeta(hazard?.title ?? 'Sayfa bulunamadı', hazard?.summary ?? 'Aradığınız sayfa bulunamadı.');
  if (!hazard) return <PublicNotFoundPage embedded />;
  return (
    <>
      <PageHero title={hazard.title} intro={hazard.summary} icon={<HazardIcon name={hazard.icon} sx={{ fontSize: 48, color: PALETTE.orangeBright }} />} />
      <Container maxWidth="lg" sx={{ py: 5 }}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap mb={3} component="nav" aria-label="Afet türleri">
          {HAZARDS.map((h) => (
            <Chip
              key={h.slug}
              label={h.title}
              component={RouterLink}
              to={`/afet/${h.slug}`}
              clickable
              color={h.slug === hazard.slug ? 'primary' : 'default'}
              aria-current={h.slug === hazard.slug ? 'page' : undefined}
            />
          ))}
        </Stack>
        <Grid container spacing={2.5}>
          {PHASES.map((phase) => (
            <Grid item xs={12} md={4} key={phase.key}>
              <Card sx={{ height: '100%', borderTop: `5px solid ${phase.color}` }}>
                <CardContent sx={{ p: 3 }}>
                  <Typography variant="h5" component="h2" gutterBottom>
                    {phase.title}
                  </Typography>
                  <Box component="ul" sx={{ pl: 2.5, m: 0, '& li': { mb: 1 } }}>
                    {hazard[phase.key].map((item) => (
                      <li key={item}>
                        <Typography>{item}</Typography>
                      </li>
                    ))}
                  </Box>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
        <Typography variant="h4" component="h2" mt={6} mb={2}>
          Hazırlık
        </Typography>
        <Grid container spacing={2.5}>
          {GUIDE.sections.map((s) => (
            <Grid item xs={12} md={4} key={s.id}>
              <Card sx={{ height: '100%' }}>
                <CardContent sx={{ p: 3 }}>
                  <Stack direction="row" spacing={1.5} alignItems="center" mb={1}>
                    <GuideIcon name={s.icon} sx={{ fontSize: 36, color: PALETTE.orange }} aria-hidden />
                    <Typography variant="h6" component="h3">
                      {s.title}
                    </Typography>
                  </Stack>
                  <Typography color="text.secondary" mb={2}>
                    {s.intro}
                  </Typography>
                  <Button component={RouterLink} to={`/rehber#${s.id}`} variant="outlined">
                    Rehberde aç
                  </Button>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Container>
    </>
  );
}
