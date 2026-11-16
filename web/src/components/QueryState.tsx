import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import { isApiError } from '../api/problem';
import { ErrorAlert } from './ErrorAlert';

/**
 * Ortak ekran durumları (mapping §0.3): yükleniyor (iskelet), yetkisiz (403), bulunamadı (404), hata.
 */
export function QueryState({
  isPending,
  error,
  onRetry,
  children,
}: {
  isPending: boolean;
  error: unknown;
  onRetry?: () => void;
  children: () => ReactNode;
}) {
  if (isPending) {
    return (
      <Stack spacing={1} data-testid="loading">
        <Skeleton variant="rounded" height={32} />
        <Skeleton variant="rounded" height={120} />
      </Stack>
    );
  }
  if (error) {
    if (isApiError(error) && error.code === 'FORBIDDEN') {
      return (
        <Box textAlign="center" py={6}>
          <Typography variant="h6" gutterBottom>
            Bu sayfayı görüntüleme yetkiniz yok
          </Typography>
          <Button component={RouterLink} to="/panel" variant="outlined">
            Panele dön
          </Button>
        </Box>
      );
    }
    if (isApiError(error) && error.code === 'NOT_FOUND') {
      return (
        <Box textAlign="center" py={6}>
          <Typography variant="h6" gutterBottom>
            Kayıt bulunamadı veya kaldırılmış.
          </Typography>
          <Button component={RouterLink} to="/panel/operasyonlar" variant="outlined">
            Listeye dön
          </Button>
        </Box>
      );
    }
    return <ErrorAlert error={error} onRetry={onRetry} />;
  }
  return <>{children()}</>;
}
