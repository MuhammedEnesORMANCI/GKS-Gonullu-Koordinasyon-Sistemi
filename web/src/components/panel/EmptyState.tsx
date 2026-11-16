import type { ReactNode } from 'react';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import InboxIcon from '@mui/icons-material/Inbox';

/** Ortak boş durum (liste sayfaları). */
export function EmptyState({ title, description, action, icon }: { title: string; description?: string; action?: ReactNode; icon?: ReactNode }) {
  return (
    <Paper variant="outlined" sx={{ p: { xs: 3, md: 5 }, textAlign: 'center' }}>
      <Stack spacing={1.5} alignItems="center">
        {icon ?? <InboxIcon sx={{ fontSize: 48, color: 'text.secondary' }} aria-hidden />}
        <Typography variant="h6" component="p">
          {title}
        </Typography>
        {description && <Typography color="text.secondary">{description}</Typography>}
        {action}
      </Stack>
    </Paper>
  );
}
