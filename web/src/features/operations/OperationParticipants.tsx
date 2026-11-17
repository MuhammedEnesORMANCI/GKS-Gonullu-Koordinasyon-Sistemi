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
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ServerPersonPicker, type PersonOption } from '../../components/ServerPersonPicker';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useServices } from '../../services/registry';
import type { Uuid } from '../../services/types';

/** RAPOR_7 madde 4: tek istekte eklenebilecek kişi sayısı (sunucuyla aynı). */
const ADD_PARTICIPANTS_MAX = 50;

/**
 * WEB-03 katılımcılar (OR-06). Katılım davet kabulüyle ya da (RAPOR_7 madde 4) ADM/KOR'un kayıtlı kullanıcıyı doğrudan
 * eklemesiyle oluşur; kayıtsız kişiler için kısayol davet ekranına gider.
 */
export function OperationParticipants({
  operationId,
  canInvite,
  canAdd = false,
}: {
  operationId: Uuid;
  canInvite: boolean;
  /** RAPOR_7 madde 4: ADM her operasyonda, KOR kendi operasyonunda (kapanmamışsa) doğrudan ekler. */
  canAdd?: boolean;
}) {
  const services = useServices();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const query = useQuery({
    queryKey: Q.participants(operationId),
    queryFn: () => services.participants.list(operationId),
  });
  const inviteLink = `/panel/davetler?kapsam=${operationId}`;

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" alignItems="center" spacing={1} mb={1}>
        <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
          Katılımcılar{query.data ? ` (${query.data.items.length}${query.data.nextCursor ? '+' : ''})` : ''}
        </Typography>
        {canAdd && (
          <Button variant="contained" size="small" onClick={() => setAdding(true)}>
            Katılımcı ekle
          </Button>
        )}
        {canInvite && (
          <Button component={RouterLink} to={inviteLink} variant="outlined" size="small">
            Davet gönder
          </Button>
        )}
      </Stack>
      {notice && (
        <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNotice(null)}>
          {notice}
        </Alert>
      )}
      {adding && (
        <AddParticipantsDialog
          operationId={operationId}
          onClose={() => setAdding(false)}
          onAdded={(names) => {
            setAdding(false);
            setNotice(`${names.length} kişi katılımcı olarak eklendi: ${names.join(', ')}. Kendilerine bildirim gönderildi.`);
          }}
        />
      )}
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Typography color="text.secondary">
              Henüz katılımcı yok. Kayıtlı kullanıcıları "Katılımcı ekle" ile doğrudan ekleyin; kayıtlı olmayanlar
              davetle katılır. Grup lideri ve görev atamaları için katılımcı gerekir.
            </Typography>
          ) : (
            <TableContainer>
              <Table size="small" aria-label="Katılımcı listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>Ad</TableCell>
                    <TableCell>İletişim</TableCell>
                    <TableCell>Gruplar</TableCell>
                    <TableCell>Açık atama</TableCell>
                    <TableCell>Katılım</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {query.data!.items.map((p) => (
                    <TableRow key={p.user.id}>
                      <TableCell>{p.user.fullName ?? p.user.displayName}</TableCell>
                      <TableCell>{[p.user.email, p.user.phone].filter(Boolean).join(' · ') || '—'}</TableCell>
                      <TableCell>{p.groups.map((g) => g.name).join(', ') || '—'}</TableCell>
                      <TableCell>{p.openAssignmentCount}</TableCell>
                      <TableCell>{formatDateTime(p.joinedAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
    </Paper>
  );
}

/**
 * RAPOR_7 madde 4: kayıtlı aktif kullanıcıları sunucuda arayıp davetsiz katılımcı ekler. Zaten katılımcı olanlar
 * aday listesinde çıkmaz; yarışta sunucu yine net hata döner (ör. "Zaten operasyon katılımcısı: Ali").
 */
function AddParticipantsDialog({
  operationId,
  onClose,
  onAdded,
}: {
  operationId: Uuid;
  onClose: () => void;
  onAdded: (names: string[]) => void;
}) {
  const services = useServices();
  const queryClient = useQueryClient();
  const intent = useIntentKey();
  const [people, setPeople] = useState<PersonOption[]>([]);
  const mutation = useMutation({
    mutationFn: ({ ids, key }: { ids: Uuid[]; key: string }) => services.participants.add(operationId, ids, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (result) => {
      intent.reset();
      void queryClient.invalidateQueries({ queryKey: Q.participants(operationId) });
      void queryClient.invalidateQueries({ queryKey: Q.operation(operationId) });
      onAdded(result.added.map((p) => p.user.displayName));
    },
  });
  const ids = people.map((p) => p.id);
  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="add-participants-title">
      <DialogTitle id="add-participants-title">Katılımcı ekle</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <Typography variant="body2" color="text.secondary">
            Kayıtlı ve etkin hesapları ada veya e-postaya göre arayın. Eklenen kişiler davet beklemeden katılımcı olur ve
            bildirim alır. Kayıtlı olmayan kişiler için davet gönderin.
          </Typography>
          <ServerPersonPicker
            queryKey={Q.participantCandidates(operationId, 'direct')}
            fetchPage={(q, page) => services.participants.candidates(operationId, q, page)}
            label="Kişiler"
            value={people}
            onChange={setPeople}
            helperText={`Tek seferde en fazla ${ADD_PARTICIPANTS_MAX} kişi.`}
            disabled={mutation.isPending}
          />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>
          Vazgeç
        </Button>
        <Button
          variant="contained"
          disabled={mutation.isPending || ids.length === 0 || ids.length > ADD_PARTICIPANTS_MAX}
          onClick={() => mutation.mutate({ ids, key: intent.keyFor(ids) })}
        >
          {mutation.isPending ? 'Ekleniyor…' : ids.length > 0 ? `${ids.length} kişiyi ekle` : 'Ekle'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

