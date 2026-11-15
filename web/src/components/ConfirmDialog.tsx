import type { ReactNode } from 'react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';

/**
 * Onay penceresi. Yıkıcı eylemlerde varsayılan odak "Vazgeç" düğmesindedir (mapping §2 WEB-03 "Kapat").
 * İşlem sürerken düğmeler kilitlidir; iyimser güncelleme yapılmaz (§6.3).
 */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  destructive = false,
  pending = false,
  error,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  destructive?: boolean;
  pending?: boolean;
  error?: ReactNode;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Dialog open={open} onClose={pending ? undefined : onClose} aria-labelledby="confirm-title" maxWidth="sm" fullWidth>
      <DialogTitle id="confirm-title">{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          {children}
          {error}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={pending} autoFocus={destructive}>
          Vazgeç
        </Button>
        <Button
          onClick={onConfirm}
          disabled={pending}
          color={destructive ? 'error' : 'primary'}
          variant="contained"
          autoFocus={!destructive}
        >
          {pending ? 'İşleniyor…' : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
