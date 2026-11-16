import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { BrandLogo } from '../../components/brand/BrandLogo';
import { BRAND, pageTitle } from '../../config/brand';

describe('Marka (tek yapılandırma)', () => {
  it('logo dosyaları sırayla denenir; hiçbiri yüklenemezse kısa ad yer tutucusu', () => {
    render(<BrandLogo />);
    const first = screen.getByRole('img', { name: `${BRAND.shortName} logosu` });
    expect(first).toHaveAttribute('src', BRAND.logoCandidates[0]);
    fireEvent.error(first);
    const second = screen.getByRole('img', { name: `${BRAND.shortName} logosu` });
    expect(second).toHaveAttribute('src', BRAND.logoCandidates[1]);
    fireEvent.error(second);
    expect(screen.getByTestId('brand-placeholder')).toHaveTextContent(BRAND.shortName);
  });

  it('sayfa başlığı marka adını taşır; acil numara yalnızca 112', () => {
    expect(pageTitle('Rehber')).toContain('Rehber');
    expect(pageTitle()).toContain(BRAND.shortName);
    expect(BRAND.emergencyNumber).toBe('112');
  });
});
