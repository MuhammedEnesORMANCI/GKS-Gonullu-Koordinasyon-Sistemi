import { useEffect, useRef, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { useSession } from '../../app/session';
import { MockBadge } from '../../components/MockBadge';
import { useIsMock, useServices } from '../../services/registry';
import { getMockPersona, setMockMfaBypass, setMockPersona } from '../../services/mock/services';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import type { MockPersona } from '../../services/mock/store';
import type { LoginResponse, MfaEnrollResponse } from '../../services/types';
import CircularProgress from '@mui/material/CircularProgress';
import Link from '@mui/material/Link';
import { ErrorAlert } from '../../components/ErrorAlert';

/** Kalan saniyeyi geri sayar (ACCOUNT_LOCKED / RATE_LIMITED `Retry-After`). */
function useCountdown(): [number, (seconds: number) => void] {
  const [remaining, setRemaining] = useState(0);
  useEffect(() => {
    if (remaining <= 0) return;
    const t = setTimeout(() => setRemaining((r) => r - 1), 1000);
    return () => clearTimeout(t);
  }, [remaining]);
  return [remaining, setRemaining];
}

/** MFA bileti yalnızca bu bileşenin belleğinde tutulur; saklanmaz, günlüğe yazılmaz (mapping §0.4). */
type Step = { kind: 'credentials' } | { kind: 'mfa'; ticket: string; enrollment: boolean };

const PERSONAS: Array<{ value: MockPersona; label: string }> = [
  { value: 'KOR', label: 'Koordinatör (KOR)' },
  { value: 'ADM', label: 'Kurum yöneticisi (ADM)' },
  { value: 'TEK', label: 'Teknik sorumlu (TEK)' },
  { value: 'UYM', label: 'Uyum görevlisi (UYM)' },
];

/**
 * WEB-01 Yönetici girişi (mapping §1.2, §2 WEB-01). ID-01 + ID-02 (`clientType=WEB`); ADM/KOR için TOTP kurulumu
 * (ID-03) ve doğrulaması (ID-04). Zorunlu parola değişikliği (ID-08) oturum açıldıktan sonra `RequireSession`'da.
 */
export function LoginPage() {
  const services = useServices();
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const authMock = useIsMock('auth');
  const contextMock = useIsMock('meContext');
  const from = (location.state as { from?: string } | null)?.from;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [persona, setPersona] = useState<MockPersona>(getMockPersona());
  const [mfaBypass, setMfaBypass] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [step, setStep] = useState<Step>({ kind: 'credentials' });
  const [lockRemaining, startLock] = useCountdown();
  const passwordRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // ID-01: girişten önce CSRF çerezi.
    services.auth.fetchCsrf().catch(() => undefined);
  }, [services]);

  if (session.status === 'authenticated') {
    return <Navigate to={from ?? '/panel'} replace />;
  }

  /** Oturum açıldı: bağlam okunur; `mustChangePassword` ise kabuk yerine parola ekranı gelir. */
  async function finishLogin() {
    await session.onLoggedIn();
    navigate(from ?? '/panel', { replace: true });
  }

  /** MFA adımından girişe dönüş (bilet geçersiz, süresi dolmuş veya hesap kilitlendi). */
  function backToCredentials(message: string | null, retryAfterSeconds?: number) {
    setStep({ kind: 'credentials' });
    setError(message);
    if (retryAfterSeconds) startLock(retryAfterSeconds);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (submitting || lockRemaining > 0) return;
    setSubmitting(true);
    setError(null);
    session.clearNotice();
    try {
      if (contextMock) setMockPersona(persona);
      if (authMock) setMockMfaBypass(mfaBypass);
      const response: LoginResponse = await services.auth.login({ email: email.trim(), password });
      setPassword('');
      if (response.mfaRequired && response.mfaTicket) {
        setStep({ kind: 'mfa', ticket: response.mfaTicket, enrollment: !!response.mfaEnrollmentRequired });
        return;
      }
      await finishLogin();
    } catch (e) {
      setPassword('');
      if (isApiError(e) && (e.code === 'ACCOUNT_LOCKED' || e.code === 'RATE_LIMITED') && e.retryAfterSeconds) {
        startLock(e.retryAfterSeconds);
      }
      setError(messageFor(e, 'login'));
      passwordRef.current?.focus();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box minHeight="100vh" display="flex" alignItems="center" justifyContent="center" bgcolor="grey.100" p={2}>
      <Card sx={{ width: '100%', maxWidth: 420 }}>
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Typography variant="h5" component="h1" sx={{ flexGrow: 1 }}>
                Yönetici girişi
              </Typography>
              <MockBadge show={authMock || contextMock} />
            </Stack>

            {session.notice && step.kind === 'credentials' && <Alert severity="info">{session.notice}</Alert>}

            {step.kind === 'credentials' && (
              <form onSubmit={handleSubmit} noValidate>
                <Stack spacing={2}>
                  {error && (
                    <Alert severity="error" role="alert">
                      {error}
                      {lockRemaining > 0 && ` (${lockRemaining} sn)`}
                    </Alert>
                  )}
                  <TextField
                    label="E-posta"
                    type="email"
                    autoComplete="username"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                    inputProps={{ maxLength: 254 }}
                    autoFocus
                  />
                  <TextField
                    label="Parola"
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    inputRef={passwordRef}
                    inputProps={{ maxLength: 128 }}
                  />
                  {contextMock && (
                    <TextField
                      select
                      label="Örnek veri rolü"
                      helperText="Örnek veri: menü ve düğmeler bu role göre çizilir."
                      value={persona}
                      onChange={(e) => setPersona(e.target.value as MockPersona)}
                    >
                      {PERSONAS.map((p) => (
                        <MenuItem key={p.value} value={p.value}>
                          {p.label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                  {authMock && (persona === 'ADM' || persona === 'KOR') && (
                    <FormControlLabel
                      control={<Checkbox checked={mfaBypass} onChange={(e) => setMfaBypass(e.target.checked)} />}
                      label="Örnek veri: TOTP adımını atla (yalnızca tanıtım; gerçek sunucu bu oturumu açmaz)"
                    />
                  )}
                  <Button
                    type="submit"
                    variant="contained"
                    disabled={submitting || lockRemaining > 0 || !email || !password}
                  >
                    {submitting ? 'Giriş yapılıyor…' : lockRemaining > 0 ? `Tekrar deneme: ${lockRemaining} sn` : 'Giriş yap'}
                  </Button>
                </Stack>
              </form>
            )}

            {step.kind === 'mfa' && (
              <MfaStep
                ticket={step.ticket}
                enrollment={step.enrollment}
                onVerified={finishLogin}
                onRestart={backToCredentials}
              />
            )}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

const MFA_EXPIRED_MESSAGE = 'Doğrulama süresi doldu, yeniden giriş yapın.';

/** TOTP gizli anahtarını 4'lü gruplar hâlinde gösterir (elle giriş kolaylığı). */
function groupSecret(secret: string): string {
  return secret.replace(/(.{4})/g, '$1 ').trim();
}

/**
 * ID-03 (yalnızca ilk kurulumda) + ID-04. Kurulum ayrı onay ucu olmadan ilk doğru kodla kalıcılaşır.
 * QR kodu bu sürümde üretilmez: anahtar doğrulayıcı uygulamaya elle girilir veya `otpauth://` bağlantısı açılır.
 */
function MfaStep({
  ticket,
  enrollment,
  onVerified,
  onRestart,
}: {
  ticket: string;
  enrollment: boolean;
  onVerified: () => Promise<void>;
  onRestart: (message: string | null, retryAfterSeconds?: number) => void;
}) {
  const services = useServices();
  const [enroll, setEnroll] = useState<MfaEnrollResponse | null>(null);
  const [enrollError, setEnrollError] = useState<unknown>(null);
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requested = useRef(false);

  useEffect(() => {
    if (!enrollment || requested.current) return;
    requested.current = true;
    services.auth
      .enrollMfa(ticket)
      .then(setEnroll)
      .catch((e: unknown) => {
        if (isApiError(e) && e.code === 'AUTH_MFA_REQUIRED') {
          onRestart(MFA_EXPIRED_MESSAGE);
          return;
        }
        setEnrollError(e);
      });
  }, [enrollment, services, ticket, onRestart]);

  async function handleVerify(event: FormEvent) {
    event.preventDefault();
    if (submitting || !/^\d{6}$/.test(code)) return;
    setSubmitting(true);
    setError(null);
    try {
      await services.auth.verifyMfa(ticket, code);
      await onVerified();
    } catch (e) {
      setCode('');
      if (isApiError(e) && e.code === 'AUTH_MFA_REQUIRED') {
        onRestart(MFA_EXPIRED_MESSAGE);
      } else if (isApiError(e) && e.code === 'ACCOUNT_LOCKED') {
        onRestart(messageFor(e, 'mfa'), e.retryAfterSeconds);
      } else {
        setError(messageFor(e, 'mfa'));
      }
    } finally {
      setSubmitting(false);
    }
  }

  const waitingForSecret = enrollment && !enroll;

  return (
    <form onSubmit={handleVerify} noValidate data-testid="mfa-step">
      <Stack spacing={2}>
        <Typography variant="subtitle1" component="h2">
          İki adımlı doğrulama
        </Typography>
        {enrollment ? (
          <>
            <Alert severity="info">
              Bu hesap için iki adımlı doğrulama (TOTP) kurulumu gerekiyor. Doğrulayıcı uygulamanıza (ör. Google
              Authenticator, Aegis) aşağıdaki anahtarı ekleyin, ardından uygulamanın gösterdiği 6 haneli kodu girin.
            </Alert>
            {enrollError ? <ErrorAlert error={enrollError} /> : null}
            {waitingForSecret && !enrollError && <CircularProgress size={24} aria-label="Anahtar hazırlanıyor" />}
            {enroll && (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">
                  Gizli anahtar (yalnızca bir kez gösterilir):
                </Typography>
                <Typography
                  data-testid="mfa-secret"
                  sx={{ fontFamily: 'monospace', fontSize: '1.1rem', letterSpacing: 1, wordBreak: 'break-all' }}
                >
                  {groupSecret(enroll.secret)}
                </Typography>
                <Link href={enroll.otpauthUri} variant="body2">
                  Bu cihazdaki doğrulayıcı uygulamada aç
                </Link>
              </Stack>
            )}
          </>
        ) : (
          <Typography variant="body2">Doğrulayıcı uygulamanızdaki 6 haneli kodu girin.</Typography>
        )}
        {error && (
          <Alert severity="error" role="alert">
            {error}
          </Alert>
        )}
        <TextField
          label="Doğrulama kodu"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          inputProps={{ inputMode: 'numeric', autoComplete: 'one-time-code', maxLength: 6 }}
          disabled={waitingForSecret}
          autoFocus
          required
        />
        <Button type="submit" variant="contained" disabled={submitting || waitingForSecret || code.length !== 6}>
          {submitting ? 'Doğrulanıyor…' : 'Doğrula'}
        </Button>
        <Button variant="text" onClick={() => onRestart(null)} disabled={submitting}>
          Girişe dön
        </Button>
      </Stack>
    </form>
  );
}
