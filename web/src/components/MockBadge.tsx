import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import ScienceOutlined from '@mui/icons-material/ScienceOutlined';

/** Ekran (veya bölüm) bellek içi örnek veriyle çalışıyorsa gösterilir. */
export function MockBadge({ show = true, detail }: { show?: boolean; detail?: string }) {
  if (!show) return null;
  return (
    <Tooltip
      title={
        detail ??
        'Bu bölüm gerçek sunucu yerine tarayıcıdaki örnek veriyle çalışıyor. Değişiklikler sayfa yenilenince kaybolur.'
      }
    >
      <Chip
        size="small"
        color="warning"
        variant="outlined"
        icon={<ScienceOutlined fontSize="small" />}
        label="Örnek veri"
        data-testid="mock-badge"
      />
    </Tooltip>
  );
}
