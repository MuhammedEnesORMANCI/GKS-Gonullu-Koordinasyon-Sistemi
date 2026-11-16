import type { ReactNode } from 'react';
import type { LocationView } from '../../services/types';

/**
 * Harita sağlayıcısı bağlanma noktası (ADR-019). Bu aşamada harita yok; WEB-08 son konumları tablo olarak gösterir.
 * Sağlayıcı seçilince (ör. Leaflet + OSM, mapping §5 — yeni bağımlılık ve atıf gerektirir) bu arayüzü uygulayan
 * bileşen `LOCATION_MAP_PROVIDER` olarak verilir; sayfa onu tablonun yanında gösterir.
 */
export interface LocationMapProvider {
  /** Atıf metni (ör. "© OpenStreetMap katkıcıları"); haritanın altında gösterilir. */
  attribution: string;
  render(props: { items: LocationView[]; height: number }): ReactNode;
}

export const LOCATION_MAP_PROVIDER: LocationMapProvider | null = null;
