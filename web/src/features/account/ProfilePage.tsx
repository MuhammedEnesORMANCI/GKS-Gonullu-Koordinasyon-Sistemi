import { useEffect, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { Q } from '../../app/queryKeys';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { useIsMock, useServices } from '../../services/registry';
import type { MeProfile, MeProfileUpdate } from '../../services/types';
import { PasswordChangeForm } from '../auth/PasswordChangePage';

/** Değişen alanlardan ID-10 kısmi güncellemesi; boş telefon `null` (sil), boş zorunlu alan gönderilmez. */
export function profileUpdate(profile: MeProfile, form: { fullName: string; displayName: string; phone: string }): MeProfileUpdate {
  const update: MeProfileUpdate = {};
  if (form.fullName.trim() !== profile.fullName) update.fullName = form.fullName.trim();
  if (form.displayName.trim() !== profile.displayName) update.displayName = form.displayName.trim();
  const phone = form.phone.trim();
  if (phone !== (profile.phone ?? '')) update.phone = phone === '' ? null : phone;
  return update;
}

/** Hesabım: profil (ID-09/10) ve parola değişikliği (ID-08). Android Hesap sekmesiyle aynı uçlar. */
export function ProfilePage() {
  const services = useServices();
  const mock = useIsMock('auth');
  const profile = useQuery({ queryKey: Q.profile(), queryFn: () => services.account.getProfile() });

  return (
    <Stack spacing={3}>
      <PageHeader title="Hesabım" mock={mock} />
      <QueryState isPending={profile.isPending} error={profile.error} onRetry={() => void profile.refetch()}>
        {() => <ProfileForm profile={profile.data!} />}
      </QueryState>
      <Card variant="outlined">
        <CardContent>
          <PasswordChangeSection />
        </CardContent>
      </Card>
    </Stack>
  );
}

function ProfileForm({ profile }: { profile: MeProfile }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ fullName: profile.fullName, displayName: profile.displayName, phone: profile.phone ?? '' });
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setForm({ fullName: profile.fullName, displayName: profile.displayName, phone: profile.phone ?? '' });
  }, [profile]);

  const save = useMutation({
    mutationFn: (update: MeProfileUpdate) => services.account.updateProfile(update),
    onSuccess: async (next) => {
      queryClient.setQueryData(Q.profile(), next);
      setSaved(true);
      // Görünen ad bağlamda ve menüde de kullanılır.
      await queryClient.invalidateQueries({ queryKey: Q.meContext() });
    },
  });
  const fields = fieldErrorMap(save.error);
  const update = profileUpdate(profile, form);
  const unchanged = Object.keys(update).length === 0;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (unchanged || save.isPending) return;
    setSaved(false);
    save.mutate(update);
  }

  const change = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setSaved(false);
    save.reset();
    setForm({ ...form, [key]: e.target.value });
  };

  return (
    <Card variant="outlined">
      <CardContent>
        <form onSubmit={handleSubmit} noValidate>
          <Stack spacing={2}>
            <Typography variant="h6" component="h2">
              Profil
            </Typography>
            {saved && <Alert severity="success">Profil güncellendi.</Alert>}
            {save.error && !(isApiError(save.error) && save.error.code === 'VALIDATION_FAILED') && (
              <Alert severity="error" role="alert">
                {messageFor(save.error)}
              </Alert>
            )}
            <TextField label="E-posta" value={profile.email} disabled helperText="E-posta adresi değiştirilemez." />
            <TextField
              label="Ad soyad"
              value={form.fullName}
              onChange={change('fullName')}
              error={!!fields.fullName}
              helperText={fields.fullName ?? '2–100 karakter'}
              inputProps={{ maxLength: 100 }}
              required
            />
            <TextField
              label="Görünen ad"
              value={form.displayName}
              onChange={change('displayName')}
              error={!!fields.displayName}
              helperText={fields.displayName ?? 'Ekip arkadaşlarınızın göreceği kısa ad (2–40 karakter)'}
              inputProps={{ maxLength: 40 }}
              required
            />
            <TextField
              label="Telefon"
              value={form.phone}
              onChange={change('phone')}
              error={!!fields.phone}
              helperText={fields.phone ?? 'Uluslararası biçimde, ör. +905551112233. Boş bırakılırsa silinir.'}
              inputProps={{ inputMode: 'tel' }}
            />
            <Button type="submit" variant="contained" disabled={unchanged || save.isPending}>
              {save.isPending ? 'Kaydediliyor…' : 'Profili kaydet'}
            </Button>
          </Stack>
        </form>
      </CardContent>
    </Card>
  );
}

function PasswordChangeSection() {
  const [done, setDone] = useState(false);
  return (
    <PasswordChangeForm
      onChanged={() => setDone(true)}
      intro={
        <>
          <Typography variant="h6" component="h2">
            Parola değiştir
          </Typography>
          {done ? (
            <Alert severity="success">Parolanız değiştirildi. Diğer cihazlardaki oturumlarınız kapatıldı.</Alert>
          ) : (
            <Alert severity="info">Parolanızı değiştirdiğinizde diğer cihazlardaki oturumlarınız kapanır.</Alert>
          )}
        </>
      }
    />
  );
}
