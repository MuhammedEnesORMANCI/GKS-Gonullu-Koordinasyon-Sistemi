import { useState } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { BRAND } from '../../config/brand';

/**
 * Kurum logosu: `BRAND.logoCandidates` sırayla denenir; hiçbiri yüklenemezse kısa ad metni (yer tutucu) gösterilir.
 * Logo çizilmez; yalnızca proje sahibinin koyduğu dosya kullanılır.
 */
export function BrandLogo({ height = 40, inverted = false }: { height?: number; inverted?: boolean }) {
  const [index, setIndex] = useState(0);
  const src = BRAND.logoCandidates[index];
  if (!src) {
    return (
      <Box
        component="span"
        aria-label={`${BRAND.shortName} logosu`}
        data-testid="brand-placeholder"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          height,
          px: 1.25,
          borderRadius: 1.5,
          border: '2px solid',
          borderColor: inverted ? 'common.white' : 'primary.main',
          color: inverted ? 'common.white' : 'primary.main',
        }}
      >
        <Typography component="span" sx={{ fontWeight: 900, letterSpacing: 1, fontSize: height * 0.42 }}>
          {BRAND.shortName}
        </Typography>
      </Box>
    );
  }
  return (
    <Box
      component="img"
      src={src}
      alt={`${BRAND.shortName} logosu`}
      onError={() => setIndex((i) => i + 1)}
      sx={{ height, width: 'auto', display: 'block' }}
    />
  );
}
