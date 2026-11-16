import { useEffect, useMemo, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import ListSubheader from '@mui/material/ListSubheader';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import NotificationsOffIcon from '@mui/icons-material/NotificationsOff';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import SendIcon from '@mui/icons-material/Send';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { PALETTE } from '../../app/theme';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { EmptyState } from '../../components/panel/EmptyState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { ChannelView, MessageView } from '../../services/types';

export const MESSAGE_MAX = 2000;

export function channelTitle(c: ChannelView): string {
  if (c.kind === 'GROUP') return `Grup: ${c.groupName ?? ''}`;
  if (c.kind === 'COORDINATION') return 'Koordinasyon';
  return 'Operasyon (tüm katılımcılar)';
}

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (ch) => (Number(ch) ^ (Math.random() * 16) >> (Number(ch) / 4)).toString(16));
}

/**
 * Mesajlar (ADR-024): solda kanal listesi (okunmamış sayılarıyla), sağda sohbet. Yeni mesajlar SSE ile anında
 * (`message.created` → kanal sorguları tazelenir). Gönderen 5 dk içinde silebilir; KOR/ADM moderasyon için gizler.
 */
export function MessagesPage() {
  const { channelId } = useParams();
  const navigate = useNavigate();
  const services = useServices();
  const mock = useIsMock('messaging');
  const channels = useQuery({ queryKey: Q.channels(), queryFn: () => services.messaging.channels() });
  const list = useMemo(() => channels.data ?? [], [channels.data]);
  const selected = list.find((c) => c.id === channelId) ?? (channelId ? undefined : list[0]);

  useEffect(() => {
    if (!channelId && list[0]) navigate(`/panel/mesajlar/${list[0].id}`, { replace: true });
  }, [channelId, list, navigate]);

  const grouped = useMemo(() => {
    const map = new Map<string, ChannelView[]>();
    for (const c of list) map.set(c.operationName, [...(map.get(c.operationName) ?? []), c]);
    return [...map.entries()];
  }, [list]);

  return (
    <>
      <PageHeader title="Mesajlar" mock={mock} />
      <QueryState isPending={channels.isPending} error={channels.error} onRetry={() => void channels.refetch()}>
        {() =>
          list.length === 0 ? (
            <EmptyState title="Erişebildiğiniz mesaj kanalı yok." description="Kanallar operasyon ve grup üyeliğinizden otomatik gelir." />
          ) : (
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="stretch">
              <Paper variant="outlined" sx={{ width: { md: 320 }, flexShrink: 0, maxHeight: { md: '72vh' }, overflowY: 'auto' }}>
                <List component="nav" aria-label="Kanallar" dense>
                  {grouped.map(([opName, items]) => (
                    <li key={opName}>
                      <ul style={{ padding: 0 }}>
                        <ListSubheader sx={{ fontWeight: 700, lineHeight: '36px' }}>{opName}</ListSubheader>
                        {items.map((c) => (
                          <ListItemButton
                            key={c.id}
                            selected={selected?.id === c.id}
                            onClick={() => navigate(`/panel/mesajlar/${c.id}`)}
                            aria-current={selected?.id === c.id ? 'page' : undefined}
                          >
                            <ListItemText
                              primary={channelTitle(c)}
                              secondary={c.readOnly ? 'Salt okunur' : c.lastMessageAt ? formatDateTime(c.lastMessageAt) : undefined}
                            />
                            {c.muted && <NotificationsOffIcon fontSize="small" sx={{ color: 'text.secondary', mr: 1 }} aria-label="Sessize alındı" />}
                            {c.unread > 0 && <Badge color="secondary" badgeContent={c.unread} max={99} aria-label={`${c.unread} okunmamış`} sx={{ mr: 1 }} />}
                          </ListItemButton>
                        ))}
                      </ul>
                    </li>
                  ))}
                </List>
              </Paper>
              <Box flexGrow={1} minWidth={0}>
                {selected ? <ChatPanel channel={selected} key={selected.id} /> : <Alert severity="info">Kanal bulunamadı.</Alert>}
              </Box>
            </Stack>
          )
        }
      </QueryState>
    </>
  );
}

function ChatPanel({ channel }: { channel: ChannelView }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const [text, setText] = useState('');
  const [key, setKey] = useState(newKey);
  const [hideTarget, setHideTarget] = useState<MessageView | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const messages = useQuery({
    queryKey: Q.channelMessages(channel.id),
    queryFn: () => services.messaging.messages(channel.id, { limit: 50 }),
  });
  const items = useMemo(() => [...(messages.data?.items ?? [])].reverse(), [messages.data]);
  const latest = items.at(-1);

  useEffect(() => {
    if (latest && channel.unread > 0) {
      void services.messaging.markRead(channel.id, latest.id).then(() => queryClient.invalidateQueries({ queryKey: Q.channels(), exact: true }));
    }
  }, [latest, channel.id, channel.unread, services, queryClient]);
  useEffect(() => {
    bottom.current?.scrollIntoView?.({ block: 'end' });
  }, [items.length]);

  const refresh = () => void queryClient.invalidateQueries({ queryKey: Q.channels() });
  const send = useMutation({
    mutationFn: () => services.messaging.send(channel.id, text.trim(), key),
    onSuccess: () => {
      setText('');
      setKey(newKey());
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: (m: MessageView) => services.messaging.remove(channel.id, m.id), onSuccess: refresh });
  const hide = useMutation({
    mutationFn: (m: MessageView) => services.messaging.hide(channel.id, m.id),
    onSuccess: () => {
      setHideTarget(null);
      refresh();
    },
  });
  const mute = useMutation({ mutationFn: (muted: boolean) => services.messaging.mute(channel.id, muted), onSuccess: refresh });
  const adminsOnly = useMutation({ mutationFn: (v: boolean) => services.messaging.setAdminsOnly(channel.id, v), onSuccess: refresh });
  const tooLong = text.length > MESSAGE_MAX;

  return (
    <Paper variant="outlined" sx={{ display: 'flex', flexDirection: 'column', height: { md: '72vh' }, minHeight: 420 }}>
      <Stack direction="row" alignItems="center" spacing={1} px={2} py={1.25} borderBottom={`1px solid ${PALETTE.border}`} flexWrap="wrap" useFlexGap>
        <Box flexGrow={1} minWidth={0}>
          <Typography variant="h6" component="h2" noWrap>
            {channelTitle(channel)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {channel.operationName}
          </Typography>
        </Box>
        {channel.readOnly && <Chip size="small" label="Salt okunur" />}
        {channel.kind === 'OPERATION' && channel.canModerate && !channel.readOnly && (
          <FormControlLabel
            control={<Switch checked={channel.adminsOnly} onChange={(e) => adminsOnly.mutate(e.target.checked)} />}
            label="Yalnızca yöneticiler yazabilir"
          />
        )}
        <Tooltip title={channel.muted ? 'Bildirimleri aç' : 'Sessize al'}>
          <IconButton onClick={() => mute.mutate(!channel.muted)} aria-label={channel.muted ? 'Bildirimleri aç' : 'Sessize al'}>
            {channel.muted ? <NotificationsOffIcon /> : <NotificationsActiveIcon />}
          </IconButton>
        </Tooltip>
      </Stack>
      <Box flexGrow={1} overflow="auto" px={2} py={1.5} role="log" aria-label="Mesajlar" aria-live="polite">
        <QueryState isPending={messages.isPending} error={messages.error} onRetry={() => void messages.refetch()}>
          {() => (
            <Stack spacing={1.25}>
              {items.length === 0 && <Typography color="text.secondary">Henüz mesaj yok.</Typography>}
              {items.map((m) => (
                <MessageBubble
                  key={m.id}
                  message={m}
                  canModerate={channel.canModerate}
                  onDelete={() => remove.mutate(m)}
                  onHide={() => setHideTarget(m)}
                />
              ))}
              <div ref={bottom} />
            </Stack>
          )}
        </QueryState>
      </Box>
      {(remove.error || mute.error || adminsOnly.error) && (
        <Box px={2}>
          <ErrorAlert error={remove.error ?? mute.error ?? adminsOnly.error} />
        </Box>
      )}
      <Box
        component="form"
        px={2}
        py={1.5}
        borderTop={`1px solid ${PALETTE.border}`}
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim() && !tooLong) send.mutate();
        }}
      >
        {channel.canWrite ? (
          <Stack direction="row" spacing={1} alignItems="flex-start">
            <TextField
              label="Mesaj"
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  if (text.trim() && !tooLong) send.mutate();
                }
              }}
              multiline
              maxRows={5}
              fullWidth
              error={tooLong}
              helperText={tooLong ? 'Mesaj en fazla 2000 karakter olabilir.' : `${text.length}/${MESSAGE_MAX} · Enter gönderir, Shift+Enter yeni satır`}
            />
            <Button type="submit" variant="contained" endIcon={<SendIcon />} disabled={!text.trim() || tooLong || send.isPending} sx={{ mt: 0.5 }}>
              Gönder
            </Button>
          </Stack>
        ) : (
          <Typography color="text.secondary">
            {channel.readOnly ? 'Operasyon kapandı; kanal salt okunur.' : 'Bu kanala yalnızca yöneticiler yazabilir.'}
          </Typography>
        )}
        {send.error && <ErrorAlert error={send.error} />}
      </Box>
      <ConfirmDialog
        open={!!hideTarget}
        title="Mesaj gizlensin mi?"
        confirmLabel="Gizle"
        destructive
        pending={hide.isPending}
        error={hide.error ? <ErrorAlert error={hide.error} /> : undefined}
        onConfirm={() => hideTarget && hide.mutate(hideTarget)}
        onClose={() => setHideTarget(null)}
      >
        <Typography>Mesaj herkes için “yönetici tarafından kaldırıldı” olarak görünecek. İşlem denetim kaydına yazılır.</Typography>
      </ConfirmDialog>
    </Paper>
  );
}

function MessageBubble({ message: m, canModerate, onDelete, onHide }: { message: MessageView; canModerate: boolean; onDelete: () => void; onHide: () => void }) {
  if (m.kind === 'SYSTEM') {
    return (
      <Alert severity="error" variant="outlined" icon={false} sx={{ alignSelf: 'center', maxWidth: 640 }}>
        <Typography variant="caption" display="block">
          Sistem · {formatDateTime(m.createdAt)}
        </Typography>
        {m.body}
      </Alert>
    );
  }
  const text = m.state === 'HIDDEN' ? 'Yönetici tarafından kaldırıldı' : m.state === 'DELETED' ? 'Mesaj silindi' : m.body;
  return (
    <Box alignSelf={m.mine ? 'flex-end' : 'flex-start'} maxWidth="80%">
      <Typography variant="caption" color="text.secondary">
        {m.mine ? 'Siz' : m.sender?.displayName} · {formatDateTime(m.createdAt)}
      </Typography>
      <Paper
        elevation={0}
        sx={{
          px: 1.5,
          py: 1,
          bgcolor: m.mine ? PALETTE.orangeSoft : '#EEF2F7',
          fontStyle: m.state === 'VISIBLE' ? 'normal' : 'italic',
          color: m.state === 'VISIBLE' ? 'text.primary' : 'text.secondary',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {text}
      </Paper>
      {m.state === 'VISIBLE' && (m.deletable || canModerate) && (
        <Stack direction="row" spacing={0.5} justifyContent={m.mine ? 'flex-end' : 'flex-start'}>
          {m.deletable && (
            <Button size="small" onClick={onDelete}>
              Sil
            </Button>
          )}
          {canModerate && !m.mine && (
            <Button size="small" color="error" onClick={onHide}>
              Gizle
            </Button>
          )}
        </Stack>
      )}
    </Box>
  );
}
