import { useState, type FormEvent, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQueryClient } from '@tanstack/react-query';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { Q } from '../../app/queryKeys';
import { useSession } from '../../app/session';
import { useServices } from '../../services/registry';

/** Backend PasswordPolicy ile aynı sınırlar (ID-08); asıl denetim sunucudadır. */
export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 128;

/**
 * ID-08 parola formu: zorunlu değişiklik sayfası ve profil sayfası ortak kullanır. Başarıda tüm eski oturumlar
 * iptal edilir, bu oturum yeni çerezlerle sürer (LD-58); bağlam yeniden okunur.
 */
export function PasswordChangeForm({
  intro,
  extraActions,
  onChanged,
  autoFocus = false,
}: {
  intro?: ReactNode;
  extraActions?: (submitting: boolean) => ReactNode;
  onChanged?: () => void;
  autoFocus?: boolean;
}) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  const mismatch = confirm !== '' && confirm !== newPassword;
  const tooShort = newPassword !== '' && [...newPassword].length < PASSWORD_MIN;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (submitting || mismatch || tooShort || !currentPassword || !newPassword) return;
    setSubmitting(true);
    setError(null);
    setFields({});
    try {
      await services.auth.changePassword({ currentPassword, newPassword });
      setCurrentPassword('');
      setNewPassword('');
      setConfirm('');
      onChanged?.();
      // Yeni oturum çerezleri yazıldı; bağlam `mustChangePassword=false` ile yeniden okunur.
      await queryClient.invalidateQueries({ queryKey: Q.meContext() });
    } catch (e) {
      setFields(fieldErrorMap(e));
      setError(isApiError(e) && e.code === 'VALIDATION_FAILED' ? null : messageFor(e));
      setCurrentPassword('');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <Stack spacing={2}>
        {intro}
        {error && (
          <Alert severity="error" role="alert">
            {error}
          </Alert>
        )}
        <TextField
          label="Mevcut parola"
          type="password"
          autoComplete="current-password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          error={!!fields.currentPassword}
          helperText={fields.currentPassword}
          inputProps={{ maxLength: PASSWORD_MAX }}
          required
          autoFocus={autoFocus}
        />
        <TextField
          label="Yeni parola"
          type="password"
          autoComplete="new-password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          error={tooShort || !!fields.newPassword}
          helperText={
            fields.newPassword ?? `En az ${PASSWORD_MIN} karakter; e-posta adresinizi veya yaygın parolaları içermemeli.`
          }
          inputProps={{ maxLength: PASSWORD_MAX }}
          required
        />
        <TextField
          label="Yeni parola (tekrar)"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          error={mismatch}
          helperText={mismatch ? 'Parolalar eşleşmiyor.' : ' '}
          inputProps={{ maxLength: PASSWORD_MAX }}
          required
        />
        <Button
          type="submit"
          variant="contained"
          disabled={submitting || mismatch || tooShort || !currentPassword || !newPassword || !confirm}
        >
          {submitting ? 'Kaydediliyor…' : 'Parolayı değiştir'}
        </Button>
        {extraActions?.(submitting)}
      </Stack>
    </form>
  );
}

/**
 * Zorunlu parola değişikliği (mapping §1.2, ID-08). `MeContext.session.mustChangePassword` iken kabuk yerine
 * gösterilir; sunucu bu durumda diğer uçları `403 AUTH_PASSWORD_CHANGE_REQUIRED` ile reddeder.
 */
export function PasswordChangePage() {
  const session = useSession();
  return (
    <Box minHeight="100vh" display="flex" alignItems="center" justifyContent="center" bgcolor="grey.100" p={2}>
      <Card sx={{ width: '100%', maxWidth: 440 }}>
        <CardContent>
          <PasswordChangeForm
            autoFocus
            intro={
              <>
                <Typography variant="h5" component="h1">
                  Yeni parola belirleyin
                </Typography>
                <Alert severity="info">
                  Devam etmek için parolanızı değiştirmeniz gerekiyor. Diğer cihazlardaki oturumlarınız kapanır.
                </Alert>
              </>
            }
            extraActions={(submitting) => (
              <Button variant="text" onClick={() => void session.logout()} disabled={submitting}>
                Çıkış
              </Button>
            )}
          />
        </CardContent>
      </Card>
    </Box>
  );
}
