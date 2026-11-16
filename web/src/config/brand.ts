/**
 * Marka yapılandırması (TEK yer). Kurum adı, kısa ad, logo ve hero görseli yolları buradan değiştirilir.
 * Görseller `web/public/brand/` altına konur; dosya yoksa logo yerine kısa ad metni, hero yerine gradyan gösterilir.
 * Logo ve kurumsal kimlik kullanım izni proje sahibindedir; logo çizilmez ve taklit edilmez.
 */
export const BRAND = {
  /** Logonun yanında ve yer tutucu olarak gösterilen kısa ad. */
  shortName: 'AFAD',
  /** Üst menüdeki kurum metni. */
  organization: 'T.C. İçişleri Bakanlığı Afet ve Acil Durum Yönetimi Başkanlığı',
  /** Sistem adı (sayfa başlıkları, panel). */
  systemName: 'Gönüllü Koordinasyon Sistemi',
  /** Sırayla denenir; hiçbiri yoksa `shortName` metni gösterilir. */
  logoCandidates: ['/brand/logo.svg', '/brand/logo.png'],
  /** Ana sayfa hero görseli; yoksa gradyan. */
  heroImage: '/brand/hero.jpg',
  /** Tek acil numara (uydurma numara yazılmaz). */
  emergencyNumber: '112',
} as const;

/** Sayfa başlığı biçimi: "Sayfa · Kısa ad". */
export function pageTitle(title?: string): string {
  return title ? `${title} · ${BRAND.shortName} ${BRAND.systemName}` : `${BRAND.shortName} ${BRAND.systemName}`;
}
