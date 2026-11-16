import Button from '@mui/material/Button';
import ForumIcon from '@mui/icons-material/Forum';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { useServices } from '../../services/registry';
import type { ChannelKind } from '../../services/types';

/**
 * Operasyon, grup ve alarm ekranlarından ilgili mesaj kanalına geçiş (ADR-024). Kanal erişilebilir değilse görünmez.
 */
export function ChannelLinkButton({ operationId, groupId, kind = groupId ? 'GROUP' : 'OPERATION', label }: {
  operationId: string;
  groupId?: string;
  kind?: ChannelKind;
  label?: string;
}) {
  const services = useServices();
  const channels = useQuery({ queryKey: Q.channels(), queryFn: () => services.messaging.channels(), staleTime: 30_000 });
  const channel = channels.data?.find((c) =>
    c.operationId === operationId && c.kind === kind && (kind !== 'GROUP' || c.groupId === groupId));
  if (!channel) return null;
  return (
    <Button component={RouterLink} to={`/panel/mesajlar/${channel.id}`} variant="outlined" startIcon={<ForumIcon />}>
      {label ?? 'Mesajlar'}
      {channel.unread > 0 ? ` (${channel.unread})` : ''}
    </Button>
  );
}
