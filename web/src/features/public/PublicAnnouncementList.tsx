import Alert from '@mui/material/Alert';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import CampaignIcon from '@mui/icons-material/Campaign';
import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { formatDateTime } from '../../components/format';
import { useServices } from '../../services/registry';

/**
 * Kamu duyuruları (yalnızca yayındakiler; kimlik doğrulamasız uç). İç duyurular burada asla gösterilmez.
 */
export function PublicAnnouncementList({ limit, compact = false }: { limit: number; compact?: boolean }) {
  const services = useServices();
  const query = useQuery({
    queryKey: Q.publicAnnouncements(limit),
    queryFn: () => services.publicAnnouncements.listPublished({ limit }),
    staleTime: 60_000,
  });
  if (query.isLoading) {
    return (
      <Stack spacing={2} aria-label="Duyurular yükleniyor">
        {Array.from({ length: Math.min(limit, 3) }, (_, i) => (
          <Skeleton key={i} variant="rounded" height={compact ? 72 : 110} />
        ))}
      </Stack>
    );
  }
  if (query.isError) {
    return <Alert severity="info">Duyurular şu an yüklenemedi. Lütfen daha sonra yeniden deneyin.</Alert>;
  }
  const items = query.data?.items ?? [];
  if (items.length === 0) {
    return <Typography color="text.secondary">Şu an yayında duyuru bulunmuyor.</Typography>;
  }
  return (
    <Stack spacing={2} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {items.map((a) => (
        <Card key={a.id} component="li">
          <CardContent sx={{ display: 'flex', gap: 2 }}>
            <CampaignIcon color="secondary" sx={{ fontSize: 32, mt: 0.25 }} aria-hidden />
            <Stack spacing={0.5} minWidth={0}>
              <Typography variant="h6" component="h3">
                {a.title}
              </Typography>
              <Typography variant="caption" color="text.secondary" component="p">
                {formatDateTime(a.publishedAt)}
              </Typography>
              <Typography sx={compact ? { display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' } : { whiteSpace: 'pre-line' }}>
                {a.body}
              </Typography>
            </Stack>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
