import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { useServices } from '../../services/registry';
import type { Uuid } from '../../services/types';

/**
 * Grup lideri/üyesi ve görev hedefi yalnızca operasyon katılımcısı olabilir (AC-09.7). Katılımcı yoksa ne yapılacağını
 * anlatır; davet izni varsa davet ekranına kısayol verir. Liste aynı sorgu anahtarını kullanır (önbellek paylaşılır).
 */
export function NoParticipantsHint({ operationId, purpose }: { operationId: Uuid; purpose: string }) {
  const services = useServices();
  const perms = usePermissions();
  const query = useQuery({
    queryKey: Q.participants(operationId),
    queryFn: () => services.participants.list(operationId),
  });
  if (!query.data || query.data.items.length > 0) return null;
  const canInvite = perms.can('invitation.create', operationId);
  return (
    <Alert
      severity="info"
      sx={{ mb: 2 }}
      action={
        canInvite ? (
          <Button component={RouterLink} to={`/panel/davetler?kapsam=${operationId}`} color="inherit" size="small">
            Davet gönder
          </Button>
        ) : undefined
      }
    >
      Bu operasyonda henüz katılımcı yok. {purpose} için önce gönüllüleri operasyona davet edin; davet kabul
      edildiğinde kişiler burada seçilebilir.
    </Alert>
  );
}
