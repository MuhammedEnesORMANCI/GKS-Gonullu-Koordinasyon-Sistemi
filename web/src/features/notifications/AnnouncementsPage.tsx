import { useMemo, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fieldErrorMap, messageFor } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { Q } from '../../app/queryKeys';
import { usePermissions, useSession } from '../../app/session';
import { useIntentKey } from '../../app/useIntentKey';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { AnnouncementPreview, AnnouncementTarget } from '../../services/types';

const ORGANIZATION = '__organization__';
export const TITLE_MIN = 3;
export const TITLE_MAX = 120;
export const BODY_MAX = 2000;

/**
 * Duyuru oluşturma ve geçmiş (NT-01 – NT-03; US-11). Hedef seçenekleri bağlamdaki etkin izinlerden gelir
 * (`announcement.send.group` KOR'un aktif operasyonu, `announcement.send.organization` ADM); asıl denetim sunucudadır.
 * Önce önizleme (alıcı sayısı), 50'den fazla alıcıda açık onay (AC-11.3), sonra gönderim.
 */
export function AnnouncementsPage() {
  const services = useServices();
  const session = useSession();
  const perms = usePermissions();
  const queryClient = useQueryClient();
  const mock = useIsMock('announcements');
  const { keyFor, reset } = useIntentKey();

  const operations = (session.context?.operations ?? []).filter(
    (op) => op.state === 'ACTIVE' && perms.can('announcement.send.group', op.id),
  );
  const canOrganization = perms.can('announcement.send.organization');
  const [scope, setScope] = useState<string>(operations[0]?.id ?? (canOrganization ? ORGANIZATION : ''));
  const [groupIds, setGroupIds] = useState<string[]>([]);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [preview, setPreview] = useState<AnnouncementPreview | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  const isOrganization = scope === ORGANIZATION;
  const target: AnnouncementTarget = useMemo(
    () => (isOrganization ? { organization: true } : { operationId: scope, ...(groupIds.length ? { groupIds } : {}) }),
    [isOrganization, scope, groupIds],
  );

  const groups = useQuery({
    queryKey: Q.groups(scope),
    queryFn: () => services.groups.list(scope),
    enabled: !!scope && !isOrganization,
  });

  const history = useQuery({
    queryKey: Q.announcements(isOrganization ? undefined : scope),
    queryFn: () => services.announcements.list(isOrganization ? {} : { operationId: scope }),
    enabled: !!scope,
  });

  const previewMutation = useMutation({
    mutationFn: () => services.announcements.preview(target),
    onSuccess: (result) => {
      setPreview(result);
      setConfirm(false);
    },
  });

  const request = { ...target, title: title.trim(), body: body.trim(), ...(preview?.confirmationRequired ? { confirm } : {}) };
  const send = useMutation({
    mutationFn: () => services.announcements.create(request, keyFor(request)),
    onSuccess: async (view) => {
      setSent(`"${view.title}" ${view.recipientCount} kişiye gönderildi.`);
      setTitle('');
      setBody('');
      setPreview(null);
      reset();
      await queryClient.invalidateQueries({ queryKey: Q.announcementsRoot() });
    },
  });

  if (!scope) {
    return (
      <Stack spacing={2}>
        <PageHeader title="Duyurular" mock={mock} />
        <Alert severity="info">
          Duyuru gönderebileceğiniz aktif bir operasyon yok. Duyuru, koordinatörü olduğunuz aktif operasyonlarda
          veya kurum yöneticisi olarak kurum genelinde gönderilebilir.
        </Alert>
      </Stack>
    );
  }

  const fields = fieldErrorMap(send.error);
  const titleLength = [...title.trim()].length;
  const bodyLength = [...body.trim()].length;
  const invalid = titleLength < TITLE_MIN || titleLength > TITLE_MAX || bodyLength < 1 || bodyLength > BODY_MAX;
  const needsConfirm = preview?.confirmationRequired && !confirm;

  function changeTarget(update: () => void) {
    update();
    setPreview(null);
    setSent(null);
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!preview || invalid || needsConfirm || send.isPending) return;
    setSent(null);
    send.mutate();
  }

  const topError = (error: unknown) =>
    error && !(isApiError(error) && error.code === 'VALIDATION_FAILED' && Object.keys(fieldErrorMap(error)).length > 0)
      ? messageFor(error)
      : null;

  return (
    <Stack spacing={3}>
      <PageHeader title="Duyurular" mock={mock} />
      <Card variant="outlined">
        <CardContent>
          <form onSubmit={handleSubmit} noValidate>
            <Stack spacing={2}>
              <Typography variant="h6" component="h2">
                Yeni duyuru
              </Typography>
              {sent && <Alert severity="success">{sent}</Alert>}
              {topError(send.error ?? previewMutation.error) && (
                <Alert severity="error" role="alert">
                  {topError(send.error ?? previewMutation.error)}
                </Alert>
              )}
              <TextField
                select
                label="Hedef"
                value={scope}
                onChange={(e) => changeTarget(() => { setScope(e.target.value); setGroupIds([]); })}
              >
                {operations.map((op) => (
                  <MenuItem key={op.id} value={op.id}>
                    {op.name} — operasyon katılımcıları
                  </MenuItem>
                ))}
                {canOrganization && <MenuItem value={ORGANIZATION}>Tüm kurum (kurum geneli duyuru)</MenuItem>}
              </TextField>
              {!isOrganization && (
                <TextField
                  select
                  label="Gruplar (boş bırakılırsa tüm operasyon)"
                  value={groupIds}
                  SelectProps={{ multiple: true }}
                  onChange={(e) => changeTarget(() => setGroupIds(typeof e.target.value === 'string' ? e.target.value.split(',') : (e.target.value as string[])))}
                  helperText={groups.isError ? 'Gruplar yüklenemedi.' : ' '}
                >
                  {(groups.data?.items ?? []).filter((g) => !g.archived).map((g) => (
                    <MenuItem key={g.id} value={g.id}>
                      {g.name} ({g.memberCount} üye)
                    </MenuItem>
                  ))}
                </TextField>
              )}
              <TextField
                label="Başlık"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                error={!!fields.title || (title !== '' && (titleLength < TITLE_MIN || titleLength > TITLE_MAX))}
                helperText={fields.title ?? `${TITLE_MIN}–${TITLE_MAX} karakter`}
                inputProps={{ maxLength: TITLE_MAX }}
                required
              />
              <TextField
                label="Duyuru metni"
                value={body}
                onChange={(e) => setBody(e.target.value)}
                error={!!fields.body}
                helperText={fields.body ?? `${bodyLength}/${BODY_MAX} — kişisel veri yazmayın.`}
                inputProps={{ maxLength: BODY_MAX }}
                multiline
                minRows={4}
                required
              />
              <Stack direction="row" spacing={2} alignItems="center">
                <Button variant="outlined" onClick={() => previewMutation.mutate()} disabled={previewMutation.isPending}>
                  {previewMutation.isPending ? 'Hesaplanıyor…' : 'Alıcıları önizle'}
                </Button>
                {preview && (
                  <Typography>
                    <strong>{preview.recipientCount}</strong> kişiye gidecek
                    {preview.recipients.length > 0 &&
                      `: ${preview.recipients.slice(0, 5).map((r) => r.displayName).join(', ')}${preview.recipients.length > 5 ? '…' : ''}`}
                  </Typography>
                )}
              </Stack>
              {preview?.confirmationRequired && (
                <FormControlLabel
                  control={<Checkbox checked={confirm} onChange={(e) => setConfirm(e.target.checked)} />}
                  label={`${preview.recipientCount} kişiye gönderileceğini onaylıyorum.`}
                />
              )}
              <Alert severity="info" variant="outlined">
                Duyuru alıcıların uygulama içi gelen kutusuna (web ve mobil) düşer. Anlık mobil bildirim (push) henüz
                etkin değildir.
              </Alert>
              <Button type="submit" variant="contained" disabled={!preview || invalid || !!needsConfirm || send.isPending}>
                {send.isPending ? 'Gönderiliyor…' : 'Duyuruyu gönder'}
              </Button>
            </Stack>
          </form>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="h6" component="h2" mb={2}>
            Gönderilen duyurular
          </Typography>
          <QueryState isPending={history.isPending} error={history.error} onRetry={() => void history.refetch()}>
            {() =>
              (history.data?.items ?? []).length === 0 ? (
                <Typography color="text.secondary">Bu hedefte henüz duyuru yok.</Typography>
              ) : (
                <Stack spacing={1}>
                  {history.data!.items.map((a) => (
                    <Stack key={a.id} direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between">
                      <Typography fontWeight={600}>{a.title}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {formatDateTime(a.createdAt)}
                        {a.createdBy && ` · ${a.createdBy.displayName}`} · {a.recipientCount} alıcı · {a.delivery.read} okudu
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              )
            }
          </QueryState>
        </CardContent>
      </Card>
    </Stack>
  );
}
