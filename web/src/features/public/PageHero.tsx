import Box from '@mui/material/Box';
import Breadcrumbs from '@mui/material/Breadcrumbs';
import Container from '@mui/material/Container';
import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { PALETTE } from '../../app/theme';

/** Halka açık iç sayfa başlığı: lacivert bant, gezinti izi, h1. */
export function PageHero({ title, intro, icon }: { title: string; intro?: string; icon?: ReactNode }) {
  return (
    <Box component="header" sx={{ bgcolor: PALETTE.navy, color: 'common.white', py: { xs: 4, md: 6 } }}>
      <Container maxWidth="lg">
        <Breadcrumbs aria-label="Konum" sx={{ color: 'rgba(255,255,255,0.9)', mb: 1.5, '& .MuiBreadcrumbs-separator': { color: 'inherit' } }}>
          <Link component={RouterLink} to="/" color="inherit" underline="hover">
            Ana Sayfa
          </Link>
          <Typography color="inherit">{title}</Typography>
        </Breadcrumbs>
        <Box display="flex" alignItems="center" gap={2}>
          {icon}
          <Typography variant="h1" sx={{ fontSize: { xs: '1.875rem', md: '2.5rem' } }}>
            {title}
          </Typography>
        </Box>
        {intro && <Typography sx={{ mt: 1.5, maxWidth: 760, fontSize: '1.0625rem', opacity: 0.95 }}>{intro}</Typography>}
      </Container>
    </Box>
  );
}
