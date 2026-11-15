import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useServices } from '../../services/registry';
import type { Uuid } from '../../services/types';

/** WEB-03 katılımcılar (OR-06). Katılım yalnızca davet kabulüyle oluşur (TD-01); kısayol davet ekranına gider. */
export function OperationParticipants({ operationId, canInvite }: { operationId: Uuid; canInvite: boolean }) {
  const services = useServices();
  const query = useQuery({
    queryKey: Q.participants(operationId),
    queryFn: () => services.participants.list(operationId),
  });
  const inviteLink = `/davetler?kapsam=${operationId}`;

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" alignItems="center" spacing={1} mb={1}>
        <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
          Katılımcılar{query.data ? ` (${query.data.items.length}${query.data.nextCursor ? '+' : ''})` : ''}
        </Typography>
        {canInvite && (
          <Button component={RouterLink} to={inviteLink} variant="outlined" size="small">
            Davet gönder
          </Button>
        )}
      </Stack>
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Typography color="text.secondary">
              Henüz katılımcı yok. Gönüllüler operasyon davetini kabul ettiğinde burada görünür; grup lideri ve görev
              atamaları için katılımcı gerekir.
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
