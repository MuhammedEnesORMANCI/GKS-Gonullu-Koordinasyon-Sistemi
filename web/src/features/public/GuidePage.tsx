import { useEffect } from 'react';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useLocation } from 'react-router-dom';
import { PALETTE } from '../../app/theme';
import { GUIDE } from './content';
import { GuideIcon } from './icons';
import { PageHero } from './PageHero';
import { usePageMeta } from './usePageMeta';

/** `/rehber`: acil durum çantası, aile afet planı, toplanma alanı (bağlantılar `#bölüm` ile). */
export function GuidePage() {
  usePageMeta(GUIDE.title, GUIDE.intro);
  const { hash } = useLocation();
  useEffect(() => {
    if (!hash) return;
    const el = document.getElementById(decodeURIComponent(hash.slice(1)));
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [hash]);
  return (
    <>
      <PageHero title={GUIDE.title} intro={GUIDE.intro} />
      <Container maxWidth="md" sx={{ py: 5 }}>
        <Stack spacing={3}>
          {GUIDE.sections.map((s) => (
            <Card key={s.id} id={s.id} component="section" aria-labelledby={`${s.id}-baslik`} sx={{ scrollMarginTop: 96 }}>
              <CardContent sx={{ p: { xs: 2.5, md: 4 } }}>
                <Stack direction="row" spacing={2} alignItems="center" mb={1.5}>
                  <Box sx={{ bgcolor: PALETTE.orangeSoft, borderRadius: 3, p: 1.25, display: 'flex' }} aria-hidden>
                    <GuideIcon name={s.icon} sx={{ fontSize: 40, color: PALETTE.orange }} />
                  </Box>
                  <Typography id={`${s.id}-baslik`} variant="h4" component="h2">
                    {s.title}
                  </Typography>
                </Stack>
                <Typography mb={2}>{s.intro}</Typography>
                <Stack component="ul" spacing={1.25} sx={{ listStyle: 'none', p: 0, m: 0 }}>
                  {s.items.map((item) => (
                    <Stack component="li" key={item} direction="row" spacing={1.25} alignItems="flex-start">
                      <CheckCircleIcon sx={{ color: PALETTE.success, mt: '2px' }} fontSize="small" aria-hidden />
                      <Typography>{item}</Typography>
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      </Container>
    </>
  );
}
