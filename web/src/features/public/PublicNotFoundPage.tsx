import Button from '@mui/material/Button';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import { usePageMeta } from './usePageMeta';

/** Bilinmeyen halka açık adres. */
export function PublicNotFoundPage({ embedded = false }: { embedded?: boolean }) {
  // Afet sayfası kendi başlığını yazar; gömülüyken tekrar yazılmaz.
  usePageMeta(embedded ? undefined : 'Sayfa bulunamadı', 'Aradığınız sayfa bulunamadı.');
  return (
    <Container maxWidth="sm" sx={{ py: 10 }}>
      <Stack spacing={2} alignItems="flex-start">
        <Typography variant="h1" sx={{ fontSize: '2rem' }}>
          Sayfa bulunamadı
        </Typography>
        <Typography>Aradığınız sayfa taşınmış ya da hiç var olmamış olabilir.</Typography>
        <Button component={RouterLink} to="/" variant="contained">
          Ana sayfaya dön
        </Button>
      </Stack>
    </Container>
  );
}
