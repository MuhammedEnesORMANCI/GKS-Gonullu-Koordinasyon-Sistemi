import Container from '@mui/material/Container';
import { PageHero } from './PageHero';
import { PublicAnnouncementList } from './PublicAnnouncementList';
import { usePageMeta } from './usePageMeta';

/** `/duyurular`: kamu duyuruları (yalnızca yayındakiler). Operasyon (iç) duyuruları burada gösterilmez. */
export function PublicAnnouncementsPage() {
  usePageMeta('Duyurular', 'Kamuya açık duyurular.');
  return (
    <>
      <PageHero title="Duyurular" intro="Kamuoyuna yönelik güncel duyurular." />
      <Container maxWidth="md" sx={{ py: 5 }}>
        <PublicAnnouncementList limit={20} />
      </Container>
    </>
  );
}
