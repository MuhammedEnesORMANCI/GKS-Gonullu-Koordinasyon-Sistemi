import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import AddIcon from '@mui/icons-material/Add';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fieldErrorMap } from '../../api/errorMessages';
import { usePermissions } from '../../app/session';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { EmptyState } from '../../components/panel/EmptyState';
import { StatusChip } from '../../components/panel/StatusChip';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { PublicAnnouncementAdminView } from '../../services/types';

const ADMIN_KEY = ['public-announcements', 'admin'] as const;

/**
 * Kamu duyuruları yönetimi (ADR-023; yalnızca ADM, `public_announcement.manage`). Taslak oluştur, düzenle, yayımla,
 * yayından kaldır. Yayındakiler halka açık sitede ve mobil uygulamada görünür; operasyon (iç) duyurularından ayrıdır.
 */
export function PublicAnnouncementAdminPage() {
  const services = useServices();
  const perms = usePermissions();
  const mock = useIsMock('publicAnnouncements');
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<PublicAnnouncementAdminView | 'new' | null>(null);
  const [confirm, setConfirm] = useState<{ row: PublicAnnouncementAdminView; action: 'publish' | 'unpublish' } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const allowed = perms.can('public_announcement.manage');

  const list = useQuery({ queryKey: ADMIN_KEY, queryFn: () => services.publicAnnouncements.list({ limit: 100 }), enabled: allowed });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ADMIN_KEY });
    void queryClient.invalidateQueries({ queryKey: ['public-announcements'] });
  };
  const transition = useMutation({
    mutationFn: ({ row, action }: { row: PublicAnnouncementAdminView; action: 'publish' | 'unpublish' }) =>
      action === 'publish'
        ? services.publicAnnouncements.publish(row.id, row.version)
        : services.publicAnnouncements.unpublish(row.id, row.version),
    onSuccess: (view) => {
      setConfirm(null);
      setNotice(view.state === 'PUBLISHED' ? 'Duyuru yayımlandı.' : 'Duyuru yayından kaldırıldı.');
      refresh();
    },
  });

  if (!allowed) {
    return (
      <>
        <PageHeader title="Kamu duyuruları" />
        <Alert severity="warning">Bu sayfa yalnızca kurum yöneticileri (ADM) içindir.</Alert>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Kamu duyuruları"
        mock={mock}
        actions={
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>
            Yeni duyuru
          </Button>
        }
      />
      <Typography color="text.secondary" mb={2}>
        Yayındaki duyurular halka açık sitede ve mobil uygulamada herkes tarafından görülür. Kişisel veya operasyona ait iç bilgi
        yazmayın.
      </Typography>
      {notice && (
        <Alert severity="success" onClose={() => setNotice(null)} sx={{ mb: 2 }}>
          {notice}
        </Alert>
      )}
      <QueryState isPending={list.isPending} error={list.error} onRetry={() => void list.refetch()}>
        {() =>
          (list.data?.items.length ?? 0) === 0 ? (
            <EmptyState title="Henüz kamu duyurusu yok." description="“Yeni duyuru” ile bir taslak oluşturun." />
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table aria-label="Kamu duyuruları">
                <TableHead>
                  <TableRow>
                    <TableCell>Başlık</TableCell>
                    <TableCell>Durum</TableCell>
                    <TableCell>Yayın tarihi</TableCell>
                    <TableCell>Son değişiklik</TableCell>
                    <TableCell align="right">İşlemler</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {list.data!.items.map((row) => (
                    <TableRow key={row.id} hover>
                      <TableCell sx={{ fontWeight: 600 }}>{row.title}</TableCell>
                      <TableCell>
                        <StatusChip kind="publicAnnouncement" value={row.state} />
                      </TableCell>
                      <TableCell>{row.publishedAt ? formatDateTime(row.publishedAt) : '—'}</TableCell>
                      <TableCell>{formatDateTime(row.updatedAt)}</TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={1} justifyContent="flex-end">
                          <Button size="small" onClick={() => setEditing(row)} aria-label={`Düzenle: ${row.title}`}>
                            Düzenle
                          </Button>
                          {row.state === 'DRAFT' ? (
                            <Button size="small" variant="contained" color="secondary" onClick={() => setConfirm({ row, action: 'publish' })}>
                              Yayımla
                            </Button>
                          ) : (
                            <Button size="small" variant="outlined" color="error" onClick={() => setConfirm({ row, action: 'unpublish' })}>
                              Yayından kaldır
                            </Button>
                          )}
                        </Stack>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {editing && (
        <PublicAnnouncementDialog
          initial={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(view) => {
            setEditing(null);
            setNotice(editing === 'new' ? 'Taslak oluşturuldu.' : 'Değişiklikler kaydedildi.');
            refresh();
            void view;
          }}
        />
      )}
      <ConfirmDialog
        open={!!confirm}
        title={confirm?.action === 'publish' ? 'Duyuru yayımlansın mı?' : 'Duyuru yayından kaldırılsın mı?'}
        confirmLabel={confirm?.action === 'publish' ? 'Yayımla' : 'Yayından kaldır'}
        destructive={confirm?.action === 'unpublish'}
        pending={transition.isPending}
        error={transition.error ? <ErrorAlert error={transition.error} /> : undefined}
        onConfirm={() => confirm && transition.mutate(confirm)}
        onClose={() => {
          setConfirm(null);
          transition.reset();
        }}
      >
        <Typography>
          {confirm?.action === 'publish'
            ? `“${confirm.row.title}” halka açık sitede ve mobil uygulamada herkese görünecek.`
            : `“${confirm?.row.title ?? ''}” halka açık siteden kaldırılacak ve taslağa dönecek.`}
        </Typography>
      </ConfirmDialog>
    </>
  );
}

function PublicAnnouncementDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: PublicAnnouncementAdminView | null;
  onClose: () => void;
  onSaved: (view: PublicAnnouncementAdminView) => void;
}) {
  const services = useServices();
  const [title, setTitle] = useState(initial?.title ?? '');
  const [body, setBody] = useState(initial?.body ?? '');
  const save = useMutation({
    mutationFn: () =>
      initial
        ? services.publicAnnouncements.update(initial.id, { title, body, version: initial.version })
        : services.publicAnnouncements.create({ title, body }),
    onSuccess: onSaved,
  });
  const fieldErrors = fieldErrorMap(save.error);
  const otherError = save.error && Object.keys(fieldErrors).length === 0 ? save.error : null;
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="pa-dialog-title">
      <DialogTitle id="pa-dialog-title">{initial ? 'Kamu duyurusunu düzenle' : 'Yeni kamu duyurusu'}</DialogTitle>
      <DialogContent>
        <Stack
          spacing={2}
          mt={1}
          component="form"
          id="pa-form"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          {initial?.state === 'PUBLISHED' && <Alert severity="info">Bu duyuru yayında; değişiklikler kaydedilince hemen görünür.</Alert>}
          <TextField
            label="Başlık"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            fullWidth
            inputProps={{ maxLength: 160 }}
            error={!!fieldErrors.title}
            helperText={fieldErrors.title ?? `${title.trim().length}/160`}
          />
          <TextField
            label="Metin"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            required
            fullWidth
            multiline
            minRows={6}
            inputProps={{ maxLength: 5000 }}
            error={!!fieldErrors.body}
            helperText={fieldErrors.body ?? `${body.trim().length}/5000`}
          />
          {otherError && <ErrorAlert error={otherError} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={save.isPending}>
          İptal
        </Button>
        <Button type="submit" form="pa-form" variant="contained" disabled={save.isPending}>
          {initial ? 'Kaydet' : 'Taslak olarak kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
