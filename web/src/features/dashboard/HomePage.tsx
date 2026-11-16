import type { ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Grid from '@mui/material/Grid';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import CrisisAlertIcon from '@mui/icons-material/CrisisAlert';
import HistoryEduIcon from '@mui/icons-material/HistoryEdu';
import PeopleIcon from '@mui/icons-material/People';
import PhonelinkRingIcon from '@mui/icons-material/PhonelinkRing';
import RecordVoiceOverIcon from '@mui/icons-material/RecordVoiceOver';
import WorkIcon from '@mui/icons-material/Work';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { PALETTE } from '../../app/theme';
import { formatDateTime } from '../../components/format';
import { useServices } from '../../services/registry';
import type { PushStatus } from '../../services/contracts';
import type { AlertCounts } from '../../services/types';
import { ACTION_LABEL } from '../audit/AuditPage';
import { OperationMapCard } from '../map/OperationMapPage';

const SELECTED_OP_KEY = 'gks.selectedOperation';

function selectedOperation(): string | undefined {
  try {
    return localStorage.getItem(SELECTED_OP_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

export function sumCounts(list: AlertCounts[]): AlertCounts {
  return list.reduce(
    (a, c) => ({
      total: a.total + c.total,
      responded: a.responded + c.responded,
      pending: a.pending + c.pending,
      unanswered: a.unanswered + c.unanswered,
      failed: a.failed + c.failed,
      needHelp: a.needHelp + c.needHelp,
    }),
    { total: 0, responded: 0, pending: 0, unanswered: 0, failed: 0, needHelp: 0 },
  );
}

const PUSH_LABEL: Record<PushStatus, { text: string; tone: 'success' | 'warning' | 'error' | 'default' }> = {
  UP: { text: 'Telefon bildirimi (FCM) çalışıyor', tone: 'success' },
  DISABLED: { text: 'Telefon bildirimi (FCM) kapalı', tone: 'warning' },
  DEGRADED: { text: 'Telefon bildirimi (FCM) çalışmıyor', tone: 'error' },
  UNKNOWN: { text: 'Telefon bildirimi durumu okunamadı', tone: 'default' },
};

/**
 * WEB-02 "Özet" (ADR-023): rol ve yetkiye göre kartlar. Veriler mevcut uçlardan; canlı akış (SSE) olayları aynı sorgu
 * anahtarlarını tazeler. Açık acil alarm varsa en üstte kırmızı, tıklanabilir bant.
 */
export function HomePage() {
  const { context } = useSession();
  const services = useServices();
  const perms = usePermissions();
  const ctx = context?.permissions;
  const anyOp = (p: string) => !!ctx && Object.values(ctx.operations).some((list) => list.includes(p));
  const canOperations = perms.isAdm || perms.isKor;
  const canAlert = anyOp('alert.view');
  const canAnnounce = !!ctx && (ctx.organization.includes('announcement.send.organization') || anyOp('announcement.send.group'));
  const canAudit = perms.can('audit.view');

  const operations = useQuery({ queryKey: Q.operations({}), queryFn: () => services.operationList.list({}), enabled: canOperations });
  const openAlerts = useQuery({
    queryKey: Q.alerts({ states: ['OPEN'] }),
    queryFn: () => services.alerts.list({ states: ['OPEN'] }),
    enabled: canAlert,
  });
  const activeOps = (operations.data?.items ?? []).filter((o) => o.state === 'ACTIVE');
  const stored = selectedOperation();
  const focusOp = activeOps.find((o) => o.id === stored) ?? activeOps[0];
  const participants = useQuery({
    queryKey: ['participants', focusOp?.id, 'summary'],
    queryFn: () => services.participants.list(focusOp!.id, { limit: 100 }),
    enabled: canOperations && !!focusOp,
  });
  const announcements = useQuery({
    queryKey: [...Q.announcementsRoot(), 'summary'],
    queryFn: () => services.announcements.list({ limit: 5 }),
    enabled: canAnnounce,
  });
  const audit = useQuery({ queryKey: ['audit', 'summary'], queryFn: () => services.audit.list({ limit: 5 }), enabled: canAudit });
  const push = useQuery({ queryKey: Q.pushStatus(), queryFn: () => services.alerts.pushStatus(), staleTime: 60_000 });

  const alerts = openAlerts.data?.items ?? [];
  const counts = sumCounts(alerts.map((a) => a.counts));

  return (
    <Stack spacing={3}>
      {canAlert && alerts.length > 0 && (
        <Alert
          severity="error"
          variant="filled"
          icon={<CrisisAlertIcon fontSize="large" />}
          action={
            <Button component={RouterLink} to="/panel/alarmlar" color="inherit" variant="outlined" sx={{ borderColor: 'rgba(255,255,255,0.7)' }}>
              Alarmlara git
            </Button>
          }
          sx={{ alignItems: 'center' }}
          role="alert"
        >
          <AlertTitle sx={{ fontWeight: 800, mb: 0 }}>{alerts.length} açık acil alarm</AlertTitle>
          {counts.needHelp > 0 ? `${counts.needHelp} kişi yardım istiyor. ` : ''}
          {counts.responded}/{counts.total} kişi yanıtladı.
        </Alert>
      )}

      <Box>
        <Typography variant="h4" component="h1">
          Hoş geldiniz{context ? `, ${context.user.displayName}` : ''}
        </Typography>
        <Typography color="text.secondary">Rolünüze göre güncel durum özeti.</Typography>
      </Box>

      <Grid container spacing={2.5}>
        {canOperations && (
          <Grid item xs={12} sm={6} lg={3}>
            <StatCard
              label="Aktif operasyon"
              value={operations.isPending ? undefined : activeOps.length}
              icon={<WorkIcon />}
              to="/panel/operasyonlar"
            />
          </Grid>
        )}
        {canAlert && (
          <Grid item xs={12} sm={6} lg={3}>
            <StatCard
              label="Açık acil alarm"
              value={openAlerts.isPending ? undefined : alerts.length}
              icon={<CrisisAlertIcon />}
              to="/panel/alarmlar"
              danger={alerts.length > 0}
            >
              {alerts.length > 0 && (
                <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap mt={1}>
                  <Chip size="small" color="success" label={`Yanıtladı: ${counts.responded}`} />
                  <Chip size="small" color="warning" label={`Bekliyor: ${counts.pending + counts.unanswered}`} />
                  <Chip size="small" color="error" label={`Yardım gerekiyor: ${counts.needHelp}`} />
                </Stack>
              )}
            </StatCard>
          </Grid>
        )}
        {canOperations && (
          <Grid item xs={12} sm={6} lg={3}>
            <StatCard
              label={focusOp ? `Gönüllü (${focusOp.name})` : 'Operasyondaki gönüllü'}
              value={!focusOp ? 0 : participants.isPending ? undefined : participants.data?.items.length ?? 0}
              suffix={participants.data?.nextCursor ? '+' : undefined}
              icon={<PeopleIcon />}
              to={focusOp ? `/panel/operasyonlar/${focusOp.id}` : '/panel/operasyonlar'}
            />
          </Grid>
        )}
        <Grid item xs={12} sm={6} lg={3}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack direction="row" spacing={1.5} alignItems="center">
                <PhonelinkRingIcon sx={{ color: PALETTE.navyLight, fontSize: 36 }} aria-hidden />
                <Typography variant="subtitle1" component="h2">
                  Sistem durumu
                </Typography>
              </Stack>
              {push.isPending ? (
                <Skeleton width="80%" />
              ) : (
                <Chip sx={{ mt: 1.5 }} color={PUSH_LABEL[push.data ?? 'UNKNOWN'].tone} label={PUSH_LABEL[push.data ?? 'UNKNOWN'].text} />
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {canOperations && <OperationMapCard />}

      <Grid container spacing={2.5}>
        {canAnnounce && (
          <Grid item xs={12} md={6}>
            <ListCard title="Son duyurular" icon={<RecordVoiceOverIcon />} to="/panel/duyurular" loading={announcements.isPending}>
              {(announcements.data?.items ?? []).map((a) => (
                <ListItem key={a.id} divider>
                  <ListItemText
                    primary={a.title}
                    secondary={`${formatDateTime(a.createdAt)} · ${a.operationName ?? 'Kurum geneli'} · ${a.recipientCount} alıcı`}
                  />
                </ListItem>
              ))}
              {announcements.data?.items.length === 0 && <EmptyLine text="Henüz duyuru yok." />}
            </ListCard>
          </Grid>
        )}
        {canAudit && (
          <Grid item xs={12} md={6}>
            <ListCard title="Son denetim olayları" icon={<HistoryEduIcon />} to="/panel/denetim" loading={audit.isPending}>
              {(audit.data?.items ?? []).map((e) => (
                <ListItem key={e.id} divider>
                  <ListItemText
                    primary={ACTION_LABEL[e.action] ?? e.action}
                    secondary={`${formatDateTime(e.at)} · ${e.actor?.displayName ?? 'Sistem'}`}
                  />
                </ListItem>
              ))}
              {audit.data?.items.length === 0 && <EmptyLine text="Kayıt yok." />}
            </ListCard>
          </Grid>
        )}
      </Grid>
      {!canOperations && !canAudit && !canAnnounce && (
        <Typography color="text.secondary">Rolünüze ait ekranlara sol menüden ulaşabilirsiniz.</Typography>
      )}
    </Stack>
  );
}

function StatCard({
  label,
  value,
  suffix,
  icon,
  to,
  danger = false,
  children,
}: {
  label: string;
  value: number | undefined;
  suffix?: string;
  icon: ReactNode;
  to: string;
  danger?: boolean;
  children?: ReactNode;
}) {
  return (
    <Card sx={{ height: '100%', borderTop: `4px solid ${danger ? PALETTE.danger : PALETTE.orangeBright}` }}>
      <CardActionArea component={RouterLink} to={to} sx={{ height: '100%' }} aria-label={`${label}: ${value ?? 'yükleniyor'}`}>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
            <Typography color="text.secondary" fontWeight={600}>
              {label}
            </Typography>
            <Box sx={{ color: danger ? PALETTE.danger : PALETTE.navyLight, '& svg': { fontSize: 32 } }} aria-hidden>
              {icon}
            </Box>
          </Stack>
          <Typography variant="h2" component="p" sx={{ color: danger ? PALETTE.danger : 'primary.main', mt: 0.5 }}>
            {value === undefined ? <Skeleton width={48} /> : `${value}${suffix ?? ''}`}
          </Typography>
          {children}
        </CardContent>
      </CardActionArea>
    </Card>
  );
}

function ListCard({ title, icon, to, loading, children }: { title: string; icon: ReactNode; to: string; loading: boolean; children: ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between">
          <Stack direction="row" spacing={1.5} alignItems="center">
            <Box sx={{ color: PALETTE.navyLight, display: 'flex' }} aria-hidden>
              {icon}
            </Box>
            <Typography variant="h6" component="h2">
              {title}
            </Typography>
          </Stack>
          <Button component={RouterLink} to={to} size="small">
            Tümü
          </Button>
        </Stack>
        {loading ? (
          <Stack spacing={1} mt={2}>
            <Skeleton />
            <Skeleton />
          </Stack>
        ) : (
          <List dense>{children}</List>
        )}
      </CardContent>
    </Card>
  );
}

function EmptyLine({ text }: { text: string }) {
  return (
    <ListItem>
      <ListItemText primary={text} primaryTypographyProps={{ color: 'text.secondary' }} />
    </ListItem>
  );
}
