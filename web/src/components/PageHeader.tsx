import type { ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { MockBadge } from './MockBadge';

export function PageHeader({ title, mock, actions }: { title: string; mock?: boolean; actions?: ReactNode }) {
  return (
    <Stack direction="row" alignItems="center" spacing={2} mb={2} flexWrap="wrap" useFlexGap>
      <Typography variant="h5" component="h1" sx={{ flexGrow: 1 }}>
        {title}
      </Typography>
      <MockBadge show={!!mock} />
      {actions}
    </Stack>
  );
}

/** ADM operasyonel salt-okur bilgi bandı (mapping §0.1 madde 2). */
export function ReadOnlyBanner({ show }: { show: boolean }) {
  if (!show) return null;
  return (
    <Alert severity="info" sx={{ mb: 2 }}>
      Salt-okur görünüm. İşlem yapmak için kendinizi bu operasyona koordinatör olarak atayın.
    </Alert>
  );
}

/** Kapanmış operasyon: kayıtlar salt-okur. */
export function ClosedOperationBanner({ show }: { show: boolean }) {
  if (!show) return null;
  return (
    <Alert severity="warning" sx={{ mb: 2 }}>
      Operasyon kapandı; kayıtlar salt-okur gösteriliyor.
    </Alert>
  );
}
