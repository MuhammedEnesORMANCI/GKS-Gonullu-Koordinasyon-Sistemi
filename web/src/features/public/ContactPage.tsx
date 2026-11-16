import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PhoneInTalkIcon from '@mui/icons-material/PhoneInTalk';
import { BRAND } from '../../config/brand';
import { PALETTE } from '../../app/theme';
import { SITE } from './content';
import { PageHero } from './PageHero';
import { usePageMeta } from './usePageMeta';

/** `/iletisim`: yalnızca 112; kurumsal iletişim bilgileri uydurulmaz (yetkili birim ekleyecek). */
export function ContactPage() {
  usePageMeta(SITE.contact.title, SITE.contact.intro);
  return (
    <>
      <PageHero title={SITE.contact.title} intro={SITE.contact.intro} />
      <Container maxWidth="md" sx={{ py: 5 }}>
        <Stack spacing={3}>
          <Card sx={{ borderLeft: `6px solid ${PALETTE.danger}` }}>
            <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 2, p: 3 }}>
              <PhoneInTalkIcon sx={{ fontSize: 48, color: PALETTE.danger }} aria-hidden />
              <Stack>
                <Typography variant="h6" component="h2">
                  Acil Çağrı Merkezi
                </Typography>
                <Typography variant="h2" component="p" color={PALETTE.danger}>
                  {BRAND.emergencyNumber}
                </Typography>
              </Stack>
            </CardContent>
          </Card>
          {SITE.contact.paragraphs.map((p) => (
            <Typography key={p}>{p}</Typography>
          ))}
        </Stack>
      </Container>
    </>
  );
}
