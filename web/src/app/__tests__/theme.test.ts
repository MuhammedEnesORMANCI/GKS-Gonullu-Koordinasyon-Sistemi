import { describe, expect, it } from 'vitest';
import { PALETTE, contrastRatio, createAppTheme } from '../theme';

describe('Tasarım sistemi: WCAG AA ve tipografi', () => {
  it('metin/zemin çiftleri en az 4.5:1', () => {
    const pairs: [string, string, string][] = [
      ['gövde metni', PALETTE.text, PALETTE.paper],
      ['gövde metni (gri zemin)', PALETTE.text, PALETTE.background],
      ['ikincil metin', PALETTE.textSecondary, PALETTE.paper],
      ['ikincil metin (gri zemin)', PALETTE.textSecondary, PALETTE.background],
      ['lacivert düğme', '#FFFFFF', PALETTE.navy],
      ['turuncu düğme', '#FFFFFF', PALETTE.orange],
      ['tehlike düğmesi', '#FFFFFF', PALETTE.danger],
      ['başarı', '#FFFFFF', PALETTE.success],
      ['uyarı', '#FFFFFF', PALETTE.warning],
      ['bağlantı', PALETTE.navyLight, PALETTE.paper],
      ['lacivert metin turuncu açık zeminde', PALETTE.navyDark, PALETTE.orangeSoft],
      ['tehlike metni açık zeminde', PALETTE.danger, PALETTE.dangerSoft],
    ];
    for (const [name, fg, bg] of pairs) {
      expect(contrastRatio(fg, bg), name).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('gövde metni en az 16 px, sistem fontu, yuvarlak köşe', () => {
    const theme = createAppTheme();
    expect(theme.typography.body1.fontSize).toBe('1rem');
    expect(theme.typography.body2.fontSize).toBe('1rem');
    expect(theme.typography.htmlFontSize).toBe(16);
    expect(theme.typography.fontFamily).toContain('system-ui');
    expect(theme.shape.borderRadius).toBeGreaterThanOrEqual(12);
    expect(theme.palette.primary.main).toBe(PALETTE.navy);
    expect(theme.palette.secondary.main).toBe(PALETTE.orange);
  });

  it('kontrast hesabı bilinen değerlerle tutarlı', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 0);
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
  });
});
