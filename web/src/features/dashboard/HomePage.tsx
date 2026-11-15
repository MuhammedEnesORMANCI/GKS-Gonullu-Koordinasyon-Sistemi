import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import { usePermissions, useSession } from '../../app/session';

/**
 * Giriş sonrası açılış sayfası. WEB-02 (role göre özet) bu turun kapsamı dışında; burada yalnızca kısayollar var.
 */
export function HomePage() {
  const { context } = useSession();
  const perms = usePermissions();
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Hoş geldiniz{context ? `, ${context.user.displayName}` : ''}
      </Typography>
      <Alert severity="info">Yönetim paneli özeti (WEB-02) bu sürümde yok.</Alert>
      {(perms.isAdm || perms.isKor) && (
        <div>
          <Button component={RouterLink} to="/operasyonlar" variant="contained">
            Operasyonlara git
          </Button>
        </div>
      )}
      {(perms.isTek || perms.isUym) && !perms.isAdm && !perms.isKor && (
        <Typography color="text.secondary">
          Rolünüze ait ekranlar (hesap durumları, denetim kayıtları) bu sürümde henüz yok.
        </Typography>
      )}
    </Stack>
  );
}
