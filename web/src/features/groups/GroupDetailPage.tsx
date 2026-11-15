import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
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
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { fieldErrorMap } from '../../api/errorMessages';
import { isApiError } from '../../api/problem';
import { shouldRetryIdempotentMutation } from '../../app/queryClient';
import { Q } from '../../app/queryKeys';
import { useIntentKey } from '../../app/useIntentKey';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ClosedOperationBanner, PageHeader, ReadOnlyBanner } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime, fromLocalInput, toLocalInput } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { GroupMemberView, GroupUpdateRequest, GroupView, MeetingPointRequest, Uuid } from '../../services/types';
import { useOperationScope } from '../operations/useOperation';
import { GROUP_DESCRIPTION_MAX, GROUP_MEMBER_LIMIT, GROUP_NAME_MAX, GROUP_NAME_MIN } from './GroupListPage';
import { ParticipantSelect } from './ParticipantPicker';

export const MEETING_ADDRESS_MAX = 300;
export const MEETING_DESCRIPTION_MAX = 500;

/** Hata sonrası ilgili sorguları yenile (mapping §0.4 "İstemci eylemi"). */
const REFRESH_CODES = new Set(['GROUP_FULL', 'LEADER_REQUIRED', 'NOT_A_PARTICIPANT', 'ALREADY_EXISTS', 'OPERATION_CLOSED', 'NOT_FOUND']);

/** WEB-05 grup detayı: GR-03, GR-04, GR-05, GR-06, GR-07, GR-08. */
export function GroupDetailPage() {
  const { opId = '', groupId = '' } = useParams();
  const services = useServices();
  const queryClient = useQueryClient();
  const mock = useIsMock('groups');
  const participantsMock = useIsMock('participants');
  const scope = useOperationScope(opId);
  const addKey = useIntentKey();
  const leaderKey = useIntentKey();
  const [newMember, setNewMember] = useState<Uuid | null>(null);
  const [removing, setRemoving] = useState<GroupMemberView | null>(null);
  const [promoting, setPromoting] = useState<GroupMemberView | null>(null);
  const [editing, setEditing] = useState(false);
  const [editingPoint, setEditingPoint] = useState(false);

  const query = useQuery({
    queryKey: Q.group(groupId),
    queryFn: () => services.groups.get(groupId),
    enabled: !!groupId,
  });

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: Q.group(groupId) }),
      queryClient.invalidateQueries({ queryKey: Q.groups(opId) }),
      queryClient.invalidateQueries({ queryKey: Q.participants(opId) }),
    ]);
  const onError = (e: unknown) => {
    if (isApiError(e) && REFRESH_CODES.has(e.code)) void refresh();
  };

  const addMember = useMutation({
    mutationFn: ({ userId, key }: { userId: Uuid; key: string }) => services.groups.addMember(groupId, userId, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: () => {
      addKey.reset();
      setNewMember(null);
      void refresh();
    },
    onError,
  });

  const removeMember = useMutation({
    mutationFn: (userId: Uuid) => services.groups.removeMember(groupId, userId),
    onSuccess: () => {
      setRemoving(null);
      void refresh();
    },
    onError,
  });

  const changeLeader = useMutation({
    mutationFn: ({ userId, key }: { userId: Uuid; key: string }) => services.groups.changeLeader(groupId, userId, key),
    retry: shouldRetryIdempotentMutation,
    onSuccess: (group) => {
      leaderKey.reset();
      setPromoting(null);
      queryClient.setQueryData(Q.group(groupId), group);
      void refresh();
    },
    onError,
  });

  const group = query.data;
  const canAdd = scope.can('membership.add') && !group?.archived;
  const canRemove = scope.can('membership.remove') && !group?.archived;
  const canLead = scope.can('membership.role.update') && !group?.archived;
  const canUpdate = scope.can('group.update') && !group?.archived;
  const canPoint = scope.can('meeting_point.update') && !group?.archived;
  const full = (group?.memberCount ?? 0) >= GROUP_MEMBER_LIMIT;

  return (
    <>
      <PageHeader
        title={group?.name ?? 'Grup'}
        mock={mock}
        actions={
          canUpdate ? (
            <Button variant="outlined" onClick={() => setEditing(true)}>
              Grup bilgisini düzenle
            </Button>
          ) : undefined
        }
      />
      <ReadOnlyBanner show={scope.admReadOnly} />
      <ClosedOperationBanner show={scope.closed || !!group?.archived} />
      <QueryState isPending={query.isPending} error={query.error} onRetry={() => void query.refetch()}>
        {() => (
          <Stack spacing={3}>
            <Paper sx={{ p: 2 }}>
              <Typography>
                <strong>Lider:</strong> {group!.leader.displayName}
              </Typography>
              <Typography>
                <strong>Açıklama:</strong> {group!.description || '—'}
              </Typography>
              <Typography>
                <strong>Üye sayısı:</strong> {group!.memberCount}/{GROUP_MEMBER_LIMIT}
              </Typography>
            </Paper>

            <section aria-labelledby="members-heading">
              <Stack direction="row" alignItems="center" spacing={1} mb={1}>
                <Typography variant="h6" id="members-heading" sx={{ flexGrow: 1 }}>
                  Üyeler
                </Typography>
              </Stack>
              {canAdd && (
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} mb={2} alignItems={{ sm: 'flex-start' }}>
                  <div style={{ flexGrow: 1, minWidth: 260 }}>
                    <ParticipantSelect
                      operationId={opId}
                      notInGroup={groupId}
                      label="Katılımcı ekle"
                      value={newMember}
                      onChange={setNewMember}
                      disabled={full}
                      helperText={full ? `Grup ${GROUP_MEMBER_LIMIT} üye sınırına ulaştı.` : 'Yalnızca operasyon katılımcıları.'}
                    />
                  </div>
                  <Button
                    variant="contained"
                    disabled={!newMember || full || addMember.isPending}
                    onClick={() => newMember && addMember.mutate({ userId: newMember, key: addKey.keyFor({ userId: newMember }) })}
                  >
                    {addMember.isPending ? 'Ekleniyor…' : 'Üye ekle'}
                  </Button>
                  {participantsMock && !mock && (
                    <Chip size="small" color="warning" variant="outlined" label="Katılımcılar: örnek veri" />
                  )}
                </Stack>
              )}
              <ErrorAlert error={addMember.error} />
              <TableContainer component={Paper}>
                <Table size="small" aria-label="Üye listesi">
                  <TableHead>
                    <TableRow>
                      <TableCell>Ad</TableCell>
                      <TableCell>Rol</TableCell>
                      <TableCell>İletişim</TableCell>
                      <TableCell>Katılım</TableCell>
                      {(canRemove || canLead) && <TableCell align="right">İşlemler</TableCell>}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {group!.members.map((m) => {
                      const isLeader = m.role === 'LEADER';
                      return (
                        <TableRow key={m.user.id}>
                          <TableCell>{m.user.fullName ?? m.user.displayName}</TableCell>
                          <TableCell>
                            {isLeader ? <Chip size="small" color="primary" label="Lider" /> : 'Üye'}
                          </TableCell>
                          <TableCell>{[m.user.email, m.user.phone].filter(Boolean).join(' · ') || '—'}</TableCell>
                          <TableCell>{formatDateTime(m.joinedAt)}</TableCell>
                          {(canRemove || canLead) && (
                            <TableCell align="right">
                              <Stack direction="row" spacing={1} justifyContent="flex-end">
                                {canLead && !isLeader && (
                                  <Button size="small" onClick={() => { changeLeader.reset(); setPromoting(m); }}>
                                    Lider yap
                                  </Button>
                                )}
                                {canRemove && (
                                  <Tooltip title={isLeader ? 'Önce yeni lider atayın' : ''}>
                                    <span>
                                      <Button
                                        size="small"
                                        color="error"
                                        disabled={isLeader}
                                        onClick={() => { removeMember.reset(); setRemoving(m); }}
                                        aria-label={`${m.user.displayName} kişisini çıkar`}
                                      >
                                        Çıkar
                                      </Button>
                                    </span>
                                  </Tooltip>
                                )}
                              </Stack>
                            </TableCell>
                          )}
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            </section>

            <section aria-labelledby="mp-heading">
              <Stack direction="row" alignItems="center" mb={1}>
                <Typography variant="h6" id="mp-heading" sx={{ flexGrow: 1 }}>
                  Toplanma noktası
                </Typography>
                {canPoint && (
                  <Button variant="outlined" onClick={() => setEditingPoint(true)}>
                    {group!.meetingPoint ? 'Güncelle' : 'Belirle'}
                  </Button>
                )}
              </Stack>
              {group!.meetingPoint ? (
                <Paper sx={{ p: 2 }}>
                  <Typography>{group!.meetingPoint.address || '—'}</Typography>
                  {group!.meetingPoint.lat !== undefined && group!.meetingPoint.lon !== undefined && (
                    <Typography variant="body2">
                      Koordinat: {group!.meetingPoint.lat}, {group!.meetingPoint.lon}
                    </Typography>
                  )}
                  {group!.meetingPoint.description && <Typography variant="body2">{group!.meetingPoint.description}</Typography>}
                  {group!.meetingPoint.time && (
                    <Typography variant="body2">Buluşma zamanı: {formatDateTime(group!.meetingPoint.time)}</Typography>
                  )}
                  <Typography variant="caption" color="text.secondary">
                    Son güncelleme: {group!.meetingPoint.updatedBy.displayName}, {formatDateTime(group!.meetingPoint.updatedAt)}
                  </Typography>
                </Paper>
              ) : (
                <Typography color="text.secondary">Toplanma noktası belirlenmedi.</Typography>
              )}
            </section>
          </Stack>
        )}
      </QueryState>

      <ConfirmDialog
        open={removing !== null}
        title="Üyeyi çıkar"
        confirmLabel="Çıkar"
        destructive
        pending={removeMember.isPending}
        error={<ErrorAlert error={removeMember.error} />}
        onClose={() => setRemoving(null)}
        onConfirm={() => removing && removeMember.mutate(removing.user.id)}
      >
        <Typography>
          {removing?.user.displayName} gruptan çıkarılsın mı? Kişinin grup ve görev erişimi bir sonraki istekte sona erer.
        </Typography>
      </ConfirmDialog>

      <ConfirmDialog
        open={promoting !== null}
        title="Lideri değiştir"
        confirmLabel="Lider yap"
        pending={changeLeader.isPending}
        error={<ErrorAlert error={changeLeader.error} />}
        onClose={() => setPromoting(null)}
        onConfirm={() =>
          promoting && changeLeader.mutate({ userId: promoting.user.id, key: leaderKey.keyFor({ userId: promoting.user.id }) })
        }
      >
        <Typography>
          {promoting?.user.displayName} grubun yeni lideri olacak; mevcut lider üye olarak kalır.
        </Typography>
      </ConfirmDialog>

      {editing && group && (
        <GroupEditDialog group={group} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); void refresh(); }} />
      )}
      {editingPoint && group && (
        <MeetingPointDialog group={group} onClose={() => setEditingPoint(false)} onSaved={() => { setEditingPoint(false); void refresh(); }} />
      )}
    </>
  );
}

function GroupEditDialog({ group, onClose, onSaved }: { group: GroupView; onClose: () => void; onSaved: () => void }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [name, setName] = useState(group.name);
  const [description, setDescription] = useState(group.description ?? '');
  const mutation = useMutation({
    mutationFn: (request: GroupUpdateRequest) => services.groups.update(group.id, request),
    onSuccess: (saved) => {
      queryClient.setQueryData(Q.group(group.id), saved);
      onSaved();
    },
  });
  const trimmed = name.trim();
  const errors: Record<string, string> = {};
  if (trimmed.length < GROUP_NAME_MIN || trimmed.length > GROUP_NAME_MAX) errors.name = `Ad ${GROUP_NAME_MIN}–${GROUP_NAME_MAX} karakter olmalı.`;
  if (description.trim().length > GROUP_DESCRIPTION_MAX) errors.description = `En fazla ${GROUP_DESCRIPTION_MAX} karakter.`;
  const server = fieldErrorMap(mutation.error);
  const request: GroupUpdateRequest = {};
  if (trimmed !== group.name) request.name = trimmed;
  // LD-52: boşaltılan açıklama `null` ile temizlenir.
  if (description.trim() !== (group.description ?? '')) request.description = description.trim() || null;

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="group-edit-title">
      <DialogTitle id="group-edit-title">Grup bilgisi</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <TextField
            label="Grup adı"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={!!(errors.name ?? server.name)}
            helperText={errors.name ?? server.name ?? `${trimmed.length}/${GROUP_NAME_MAX}`}
            inputProps={{ maxLength: GROUP_NAME_MAX }}
          />
          <TextField
            label="Açıklama"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
            error={!!(errors.description ?? server.description)}
            helperText={errors.description ?? server.description ?? `${description.trim().length}/${GROUP_DESCRIPTION_MAX}`}
            inputProps={{ maxLength: GROUP_DESCRIPTION_MAX }}
          />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>Vazgeç</Button>
        <Button
          variant="contained"
          disabled={mutation.isPending || Object.keys(errors).length > 0 || Object.keys(request).length === 0}
          onClick={() => mutation.mutate(request)}
        >
          {mutation.isPending ? 'Kaydediliyor…' : 'Kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function parseCoordinate(value: string): number | undefined {
  if (value.trim() === '') return undefined;
  const n = Number(value.replace(',', '.'));
  return Number.isFinite(n) ? n : Number.NaN;
}

function MeetingPointDialog({ group, onClose, onSaved }: { group: GroupView; onClose: () => void; onSaved: () => void }) {
  const services = useServices();
  const current = group.meetingPoint;
  const [address, setAddress] = useState(current?.address ?? '');
  const [lat, setLat] = useState(current?.lat !== undefined ? String(current.lat) : '');
  const [lon, setLon] = useState(current?.lon !== undefined ? String(current.lon) : '');
  const [description, setDescription] = useState(current?.description ?? '');
  const [time, setTime] = useState(toLocalInput(current?.time));
  const mutation = useMutation({
    mutationFn: (request: MeetingPointRequest) => services.groups.setMeetingPoint(group.id, request),
    onSuccess: onSaved,
  });

  const latN = parseCoordinate(lat);
  const lonN = parseCoordinate(lon);
  const timeIso = fromLocalInput(time);
  const errors: Record<string, string> = {};
  if (address.trim().length > MEETING_ADDRESS_MAX) errors.address = `En fazla ${MEETING_ADDRESS_MAX} karakter.`;
  if (description.trim().length > MEETING_DESCRIPTION_MAX) errors.description = `En fazla ${MEETING_DESCRIPTION_MAX} karakter.`;
  if ((latN === undefined) !== (lonN === undefined)) errors.lat = 'Enlem ve boylam birlikte girilmelidir.';
  if (latN !== undefined && (Number.isNaN(latN) || latN < -90 || latN > 90)) errors.lat = 'Enlem −90 ile 90 arasında olmalı.';
  if (lonN !== undefined && (Number.isNaN(lonN) || lonN < -180 || lonN > 180)) errors.lon = 'Boylam −180 ile 180 arasında olmalı.';
  if (!address.trim() && latN === undefined && lonN === undefined) errors.address = 'Adres veya koordinat girilmelidir.';
  if (!timeIso) errors.time = 'Buluşma zamanı zorunludur.';
  const server = fieldErrorMap(mutation.error);

  function submit() {
    if (Object.keys(errors).length > 0 || !timeIso) return;
    const request: MeetingPointRequest = { time: timeIso };
    if (address.trim()) request.address = address.trim();
    if (latN !== undefined && lonN !== undefined) {
      request.lat = latN;
      request.lon = lonN;
    }
    if (description.trim()) request.description = description.trim();
    mutation.mutate(request);
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="mp-edit-title">
      <DialogTitle id="mp-edit-title">Toplanma noktası</DialogTitle>
      <DialogContent>
        <Stack spacing={2} mt={1}>
          <Alert severity="info">Harita üzerinde işaretleme bu sürümde yok; koordinatı elle girin. Adres metni otomatik bulunmaz.</Alert>
          <TextField
            label="Adres"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            error={!!(errors.address ?? server.address)}
            helperText={errors.address ?? server.address ?? `${address.trim().length}/${MEETING_ADDRESS_MAX}`}
            inputProps={{ maxLength: MEETING_ADDRESS_MAX }}
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Enlem"
              value={lat}
              onChange={(e) => setLat(e.target.value)}
              error={!!(errors.lat ?? server.lat)}
              helperText={errors.lat ?? server.lat}
              inputProps={{ inputMode: 'decimal' }}
            />
            <TextField
              label="Boylam"
              value={lon}
              onChange={(e) => setLon(e.target.value)}
              error={!!(errors.lon ?? server.lon)}
              helperText={errors.lon ?? server.lon}
              inputProps={{ inputMode: 'decimal' }}
            />
          </Stack>
          <TextField
            label="Açıklama"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
            error={!!(errors.description ?? server.description)}
            helperText={errors.description ?? server.description ?? `${description.trim().length}/${MEETING_DESCRIPTION_MAX}`}
            inputProps={{ maxLength: MEETING_DESCRIPTION_MAX }}
          />
          <TextField
            label="Buluşma zamanı"
            type="datetime-local"
            value={time}
            onChange={(e) => setTime(e.target.value)}
            required
            InputLabelProps={{ shrink: true }}
            error={!!(errors.time ?? server.time)}
            helperText={errors.time ?? server.time ?? 'Türkiye saati'}
          />
          <ErrorAlert error={mutation.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>Vazgeç</Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending || Object.keys(errors).length > 0}>
          {mutation.isPending ? 'Kaydediliyor…' : 'Kaydet'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
