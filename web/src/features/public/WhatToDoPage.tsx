import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { PALETTE } from '../../app/theme';
import { EMERGENCY } from './content';
import { PageHero } from './PageHero';
import { usePageMeta } from './usePageMeta';

/** `/ne-yapmali`: afet anında sırayla uygulanacak temel adımlar. */
export function WhatToDoPage() {
  usePageMeta(EMERGENCY.title, EMERGENCY.intro);
  return (
    <>
      <PageHero title={EMERGENCY.title} intro={EMERGENCY.intro} />
      <Container maxWidth="md" sx={{ py: 5 }}>
        <Alert severity="error" variant="filled" sx={{ mb: 3, fontSize: '1.125rem', fontWeight: 700 }}>
          {EMERGENCY.callout}
        </Alert>
        <Stack component="ol" spacing={2} sx={{ listStyle: 'none', p: 0, m: 0 }}>
          {EMERGENCY.steps.map((step, i) => (
            <Card component="li" key={step.title}>
              <CardContent sx={{ display: 'flex', gap: 2.5, alignItems: 'flex-start', p: 3 }}>
                <Box
                  aria-hidden
                  sx={{
                    flexShrink: 0, width: 48, height: 48, borderRadius: '50%', bgcolor: PALETTE.orange, color: 'common.white',
                    display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: '1.25rem',
                  }}
                >
                  {i + 1}
                </Box>
                <Stack spacing={0.5}>
                  <Typography variant="h5" component="h2">
                    {step.title}
                  </Typography>
                  <Typography>{step.text}</Typography>
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      </Container>
    </>
  );
}
