import { alpha, createTheme, type Theme } from '@mui/material/styles';
import { trTR } from '@mui/material/locale';

/**
 * Tasarım sistemi (TEK yer): koyu lacivert + turuncu vurgu, beyaz/açık gri zemin, yuvarlak köşeli kartlar.
 * Metin/zemin çiftleri WCAG AA (≥ 4.5:1) sağlar; `theme.test.ts` denetler. Gövde metni en az 16 px.
 * Sistem fontları kullanılır (indirme yok).
 */
export const PALETTE = {
  navy: '#0B2D57',
  navyDark: '#071E3D',
  navyLight: '#1C4A80',
  orange: '#C2410C', // beyaz metinle AA (düğme, vurgu)
  orangeBright: '#F97316', // yalnızca süs (çizgi, ikon zemini); üstünde metin yok
  orangeSoft: '#FFF1E6',
  background: '#F3F5F8',
  paper: '#FFFFFF',
  text: '#14213D',
  textSecondary: '#4A5568',
  border: '#D9DEE7',
  danger: '#B42318',
  dangerSoft: '#FEECEB',
  success: '#1E6B3A',
  warning: '#8A4B00',
  info: '#1C4A80',
} as const;

export const FONT_STACK =
  'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", "Liberation Sans", sans-serif';

export function createAppTheme(): Theme {
  return createTheme(
    {
      palette: {
        mode: 'light',
        primary: { main: PALETTE.navy, dark: PALETTE.navyDark, light: PALETTE.navyLight, contrastText: '#FFFFFF' },
        secondary: { main: PALETTE.orange, light: PALETTE.orangeBright, contrastText: '#FFFFFF' },
        error: { main: PALETTE.danger, contrastText: '#FFFFFF' },
        success: { main: PALETTE.success, contrastText: '#FFFFFF' },
        warning: { main: PALETTE.warning, contrastText: '#FFFFFF' },
        info: { main: PALETTE.info, contrastText: '#FFFFFF' },
        background: { default: PALETTE.background, paper: PALETTE.paper },
        text: { primary: PALETTE.text, secondary: PALETTE.textSecondary },
        divider: PALETTE.border,
      },
      shape: { borderRadius: 12 },
      typography: {
        fontFamily: FONT_STACK,
        htmlFontSize: 16,
        fontSize: 16,
        body1: { fontSize: '1rem', lineHeight: 1.6 },
        body2: { fontSize: '1rem', lineHeight: 1.5 },
        caption: { fontSize: '0.875rem' },
        button: { fontSize: '1rem', fontWeight: 600, textTransform: 'none' },
        h1: { fontSize: '2.5rem', fontWeight: 800, lineHeight: 1.15 },
        h2: { fontSize: '2rem', fontWeight: 800, lineHeight: 1.2 },
        h3: { fontSize: '1.625rem', fontWeight: 700 },
        h4: { fontSize: '1.5rem', fontWeight: 700 },
        h5: { fontSize: '1.25rem', fontWeight: 700 },
        h6: { fontSize: '1.125rem', fontWeight: 700 },
        subtitle1: { fontSize: '1.0625rem', fontWeight: 600 },
        subtitle2: { fontSize: '1rem', fontWeight: 600 },
      },
      components: {
        MuiCssBaseline: {
          styleOverrides: {
            body: { backgroundColor: PALETTE.background },
            // Klavyeyle gezinmede görünür odak halkası.
            ':focus-visible': { outline: `3px solid ${PALETTE.orangeBright}`, outlineOffset: 2 },
            a: { color: PALETTE.navyLight },
          },
        },
        MuiButton: {
          defaultProps: { disableElevation: true },
          styleOverrides: {
            root: { borderRadius: 10, paddingInline: 18, minHeight: 44 },
            sizeSmall: { minHeight: 36, paddingInline: 12, fontSize: '0.9375rem' },
          },
        },
        MuiIconButton: { styleOverrides: { root: { '&:focus-visible': { outline: `3px solid ${PALETTE.orangeBright}` } } } },
        MuiPaper: { styleOverrides: { rounded: { borderRadius: 16 } } },
        MuiCard: {
          defaultProps: { elevation: 0 },
          styleOverrides: {
            root: { borderRadius: 16, border: `1px solid ${PALETTE.border}`, boxShadow: '0 1px 2px rgba(11,45,87,0.06)' },
          },
        },
        MuiAppBar: { defaultProps: { elevation: 0 } },
        MuiChip: { styleOverrides: { root: { fontWeight: 600, borderRadius: 8 } } },
        MuiTableHead: {
          styleOverrides: {
            root: {
              '& .MuiTableCell-head': { fontWeight: 700, backgroundColor: '#EEF2F7', color: PALETTE.text },
            },
          },
        },
        MuiTableRow: { styleOverrides: { root: { '&.MuiTableRow-hover:hover': { backgroundColor: alpha(PALETTE.navy, 0.04) } } } },
        MuiTextField: { defaultProps: { fullWidth: false } },
        MuiOutlinedInput: { styleOverrides: { root: { backgroundColor: '#FFFFFF' } } },
        MuiAlert: { styleOverrides: { root: { borderRadius: 12, fontSize: '1rem' } } },
        MuiDialog: { styleOverrides: { paper: { borderRadius: 16 } } },
        MuiDialogActions: { styleOverrides: { root: { padding: '12px 24px 20px', gap: 8 } } },
        MuiListItemButton: {
          styleOverrides: {
            root: {
              borderRadius: 10,
              '&.Mui-selected': { backgroundColor: alpha(PALETTE.orangeBright, 0.14), color: PALETTE.navyDark, fontWeight: 700 },
            },
          },
        },
      },
    },
    trTR,
  );
}

/** WCAG göreli parlaklık ve kontrast oranı (AA: normal metin ≥ 4.5, büyük metin ≥ 3). */
export function contrastRatio(foreground: string, background: string): number {
  const channel = (hex: string, i: number) => {
    const v = parseInt(hex.replace('#', '').slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const lum = (hex: string) => 0.2126 * channel(hex, 0) + 0.7152 * channel(hex, 2) + 0.0722 * channel(hex, 4);
  const x = lum(foreground);
  const y = lum(background);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}
