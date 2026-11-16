import hazardsJson from '../../content/hazards.json';
import guideJson from '../../content/guide.json';
import emergencyJson from '../../content/emergency.json';
import siteJson from '../../content/site.json';

/**
 * Halka açık site içeriği: metinler `web/src/content/*.json` dosyalarındadır (koddan ayrı, kolay düzenlenir).
 * Android aynı dosyaları uygulamaya gömer (internetsiz). Her dosyanın `_dogrulama` notu: yetkili kaynaklarla doğrulanmalı.
 */
export type HazardIconKey = 'earthquake' | 'flood' | 'landslide' | 'fire' | 'winter' | 'other';
export type GuideIconKey = 'bag' | 'family' | 'place';

export interface Hazard {
  slug: string;
  title: string;
  icon: HazardIconKey;
  summary: string;
  before: string[];
  during: string[];
  after: string[];
}

export interface GuideSection {
  id: string;
  title: string;
  icon: GuideIconKey;
  intro: string;
  items: string[];
}

export const HAZARDS = hazardsJson.hazards as Hazard[];
export const GUIDE = guideJson as { title: string; intro: string; sections: GuideSection[] };
export const EMERGENCY = emergencyJson as { title: string; intro: string; callout: string; steps: { title: string; text: string }[] };
export const SITE = siteJson;

export function findHazard(slug: string | undefined): Hazard | undefined {
  return HAZARDS.find((h) => h.slug === slug);
}
