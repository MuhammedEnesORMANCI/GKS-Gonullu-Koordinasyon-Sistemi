import { useState } from 'react';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader, ReadOnlyBanner } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { useIsMock, useServices } from '../../services/registry';
import { NoParticipantsHint } from '../operations/NoParticipantsHint';
import type { GroupCreateRequest, Uuid } from '../../services/types';
import { useOperationScope } from '../operations/useOperation';
import { ParticipantMultiSelect, ParticipantSelect } from './ParticipantPicker';

export const GROUP_NAME_MIN = 2;
export const GROUP_NAME_MAX = 80;
export const GROUP_DESCRIPTION_MAX = 1000;
export const GROUP_MEMBER_LIMIT = 50;

/** WEB-05 grup listesi (GR-02) ve grup oluşturma (GR-01). */
export function GroupListPage() {
  const { opId = '' } = useParams();
  const services = useServices();
  const mock = useIsMock('groups');
  const scope = useOperationScope(opId);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [creating, setCreating] = useState(false);

  const query = useQuery({
    queryKey: [...Q.groups(opId), { includeArchived }],
    queryFn: () => services.groups.list(opId, { includeArchived }),
    enabled: !!opId,
  });

  const canCreate = scope.can('group.create.operational');

  return (
    <>
      <PageHeader
        title={`Gruplar${scope.operation.data ? ` — ${scope.operation.data.name}` : ''}`}
        mock={mock}
        actions={
          canCreate ? (
            <Button variant="contained" onClick={() => setCreating(true)}>
              Grup oluştur
            </Button>
          ) : undefined
        }
      />
      <ReadOnlyBanner show={scope.admReadOnly} />
      <ClosedOperationBanner show={scope.closed} />
      {canCreate && <NoParticipantsHint operationId={opId} purpose="Grup lideri ve üyeleri seçmek" />}
      <FormControlLabel
        control={<Switch checked={includeArchived} onChange={(e) => setIncludeArchived(e.target.checked)} />}
        label="Arşivlenmiş grupları da göster"
      />
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() =>
          query.data!.items.length === 0 ? (
            <Stack spacing={1} alignItems="flex-start" mt={2}>
              <Typography color="text.secondary">
                {canCreate
                  ? 'Henüz grup yok. Katılımcıları ekiplere ayırmak için grup oluşturun; her grubun bir lideri olur.'
                  : 'Bu operasyonda grup yok.'}
              </Typography>
              {canCreate && (
                <Button variant="outlined" onClick={() => setCreating(true)}>
                  Grup oluştur
                </Button>
              )}
            </Stack>
          ) : (
            <TableContainer component={Paper} sx={{ mt: 1 }}>
              <Table size="small" aria-label="Grup listesi">
                <TableHead>
                  <TableRow>
                    <TableCell>Ad</TableCell>
                    <TableCell>Lider</TableCell>
                    <TableCell align="right">Üye</TableCell>
                    <TableCell />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {query.data!.items.map((g) => (
                    <TableRow key={g.id} hover>
                      <TableCell>
                        <RouterLink to={`/operasyonlar/${opId}/gruplar/${g.id}`}>{g.name}</RouterLink>
                      </TableCell>
                      <TableCell>{g.leader.displayName}</TableCell>
                      <TableCell align="right">
                        {g.memberCount}/{GROUP_MEMBER_LIMIT}
                      </TableCell>
                      <TableCell>{g.archived && <Chip size="small" label="Arşivlendi" />}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        }
      </QueryState>
      {creating && <GroupCreateDialog operationId={opId} onClose={() => setCreating(false)} />}
    </>
  );
}

function GroupCreateDialog({ operationId, onClose }: { operationId: Uuid; onClose: () => void }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const intent = useIntentKey();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [leader, setLeader] = useState<Uuid | null>(null);
  const [members, setMembers] = useState<Uuid[]>([]);
  const [touched, setTouched] = useState(false);

  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: GroupCreateRequest; key: string }) =>
      services.groups.create(operationId, request, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (group) => {
      intent.reset();
      void queryClient.invalidateQueries({ queryKey: Q.groups(operationId) });
      void queryClient.invalidateQueries({ queryKey: Q.participants(operationId) });
      queryClient.setQueryData(Q.group(group.id), group);
      navigate(`/operasyonlar/${operationId}/gruplar/${group.id}`);
    },
    onError: (e) => {
      if (isApiError(e) && (e.code === 'NOT_A_PARTICIPANT' || e.code === 'OPERATION_CLOSED')) {
        void queryClient.invalidateQueries({ queryKey: Q.participants(operationId) });
        void queryClient.invalidateQueries({ queryKey: Q.operation(operationId) });
      }
    },
  });

  const trimmed = name.trim();
  const errors: Record<string, string> = {};
  if (trimmed.length < GROUP_NAME_MIN || trimmed.length > GROUP_NAME_MAX) {
    errors.name = `Ad ${GROUP_NAME_MIN}–${GROUP_NAME_MAX} karakter olmalı.`;
  }
  if (description.trim().length > GROUP_DESCRIPTION_MAX) errors.description = `En fazla ${GROUP_DESCRIPTION_MAX} karakter.`;
  // AC-09.5: lider seçilmeden form gönderilemez.
  if (!leader) errors.leaderUserId = 'Lider seçimi zorunludur.';
  const server = fieldErrorMap(mutation.error);
  const leaderRequired = isApiError(mutation.error) && mutation.error.code === 'LEADER_REQUIRED';

  function submit() {
    setTouched(true);
    if (Object.keys(errors).length > 0 || !leader) return;
    const request: GroupCreateRequest = {
      name: trimmed,
      leaderUserId: leader,
      memberUserIds: members.filter((m) => m !== leader),
    };
    if (description.trim()) request.description = description.trim();
    mutation.mutate({ request, key: intent.keyFor(request) });
  }

  const show = (field: string) => (touched ? (errors[field] ?? server[field]) : server[field]);

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="group-create-title">
      <DialogTitle id="group-create-title">Grup oluştur</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <TextField
            label="Grup adı"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            error={!!show('name')}
            helperText={show('name') ?? `${trimmed.length}/${GROUP_NAME_MAX}`}
            inputProps={{ maxLength: GROUP_NAME_MAX }}
          />
          <TextField
            label="Açıklama"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
            error={!!show('description')}
            helperText={show('description') ?? `${description.trim().length}/${GROUP_DESCRIPTION_MAX}`}
            inputProps={{ maxLength: GROUP_DESCRIPTION_MAX }}
          />
          <ParticipantSelect
            operationId={operationId}
            label="Lider"
            required
            value={leader}
            onChange={setLeader}
            error={!!show('leaderUserId') || leaderRequired}
            helperText={show('leaderUserId') ?? 'Yalnızca operasyon katılımcıları seçilebilir.'}
          />
          <ParticipantMultiSelect
            operationId={operationId}
            label="Üyeler (isteğe bağlı)"
            value={members}
            onChange={setMembers}
            exclude={leader ? [leader] : []}
            max={GROUP_MEMBER_LIMIT - 1}
            helperText={`Lider dahil en fazla ${GROUP_MEMBER_LIMIT} kişi.`}
          />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>
          Vazgeç
        </Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending || (touched && Object.keys(errors).length > 0)}>
          {mutation.isPending ? 'Oluşturuluyor…' : 'Oluştur'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
