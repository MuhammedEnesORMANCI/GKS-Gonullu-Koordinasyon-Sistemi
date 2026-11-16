import Alert from '@mui/material/Alert';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { SITE } from './content';
import { PageHero } from './PageHero';
import { usePageMeta } from './usePageMeta';

/** `/gonulluluk`: sistem davetle çalışır; herkese açık kayıt formu yoktur (proje sahibinin kararı bekleniyor). */
export function VolunteerPage() {
  usePageMeta(SITE.volunteer.title, SITE.volunteer.intro);
  return (
    <>
      <PageHero title={SITE.volunteer.title} intro={SITE.volunteer.intro} />
      <Container maxWidth="md" sx={{ py: 5 }}>
        <Stack spacing={2}>
          {SITE.volunteer.paragraphs.map((p) => (
            <Typography key={p}>{p}</Typography>
          ))}
          <Alert severity="info">Davet kodunuz varsa hesabınızı mobil uygulamadaki “Davet koduyla kayıt” ekranından oluşturabilirsiniz.</Alert>
        </Stack>
      </Container>
    </>
  );
}
