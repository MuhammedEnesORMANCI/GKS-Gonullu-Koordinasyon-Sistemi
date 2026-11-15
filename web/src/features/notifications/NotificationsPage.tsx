import { useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Typography from '@mui/material/Typography';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { formatDateTime } from '../../components/format';
import { useIsMock, useServices } from '../../services/registry';
import type { InboxItem, NotificationCategory } from '../../services/types';

export const CATEGORY_LABEL: Record<NotificationCategory, string> = {
  ALERT: 'Acil',
  TASK: 'Görev',
  ANNOUNCEMENT: 'Duyuru',
  MEETING_POINT: 'Toplanma noktası',
  OPERATION_INVITE: 'Davet',
  SYSTEM: 'Grup',
};

/** Uygulama içi gelen kutusu (NT-04/05, LD-60). Bir bildirimi açmak onu okundu işaretler. */
export function NotificationsPage() {
  const services = useServices();
  const queryClient = useQueryClient();
  const mock = useIsMock('notifications');
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [open, setOpen] = useState<string | null>(null);

  const inbox = useInfiniteQuery({
    queryKey: Q.inbox(unreadOnly),
    queryFn: ({ pageParam }) => services.notifications.list({ unreadOnly, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
  });

  const markRead = useMutation({
    mutationFn: (id: string) => services.notifications.markRead(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: Q.inboxRoot() });
    },
  });

  function toggle(item: InboxItem) {
    setOpen(open === item.id ? null : item.id);
    if (!item.readAt) markRead.mutate(item.id);
  }

  const items = inbox.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <Stack spacing={2}>
      <PageHeader
        title="Bildirimler"
        mock={mock}
        actions={
          <FormControlLabel
            control={<Switch checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} />}
            label="Yalnızca okunmamışlar"
          />
        }
      />
      {markRead.error && <ErrorAlert error={markRead.error} />}
      <QueryState isPending={inbox.isPending} error={inbox.error} onRetry={() => void inbox.refetch()}>
        {() =>
          items.length === 0 ? (
            <Typography color="text.secondary">
              {unreadOnly ? 'Okunmamış bildiriminiz yok.' : 'Henüz bildiriminiz yok.'}
            </Typography>
          ) : (
            <Stack spacing={1} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
              {items.map((item) => (
                <Box component="li" key={item.id}>
                  <Card variant="outlined" sx={{ borderLeft: 4, borderLeftColor: item.readAt ? 'divider' : 'primary.main' }}>
                    <CardActionArea onClick={() => toggle(item)} aria-expanded={open === item.id}>
                      <CardContent>
                        <Stack direction="row" spacing={1} alignItems="center" mb={0.5}>
                          <Chip size="small" label={CATEGORY_LABEL[item.category] ?? item.category} />
                          <Typography variant="caption" color="text.secondary">
                            {formatDateTime(item.createdAt)}
                          </Typography>
                          {!item.readAt && <Chip size="small" color="primary" label="Okunmadı" />}
                        </Stack>
                        <Typography variant="subtitle1" fontWeight={item.readAt ? 400 : 700}>
                          {item.title}
                        </Typography>
                        {item.body && (
                          <Typography
                            variant="body2"
                            color="text.secondary"
                            sx={open === item.id ? { whiteSpace: 'pre-wrap' } : { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                          >
                            {item.body}
                          </Typography>
                        )}
                      </CardContent>
                    </CardActionArea>
                  </Card>
                </Box>
              ))}
            </Stack>
          )
        }
      </QueryState>
      {inbox.hasNextPage && (
        <Box>
          <Button onClick={() => void inbox.fetchNextPage()} disabled={inbox.isFetchingNextPage}>
            {inbox.isFetchingNextPage ? 'Yükleniyor…' : 'Daha eski bildirimler'}
          </Button>
        </Box>
      )}
    </Stack>
  );
}
