package org.gks.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tasarım sistemi (web `web/src/app/theme.ts` ile aynı palet): koyu lacivert + turuncu vurgu, açık gri zemin,
 * yuvarlak köşeli kartlar. Metin/zemin çiftleri WCAG AA (≥ 4.5:1; `GksPaletteTest`). Marka rengi korunur: sistem
 * (dinamik) renkleri kullanılmaz. Gövde metni en az 16 sp.
 */
object GksPalette {
	val Navy = Color(0xFF0B2D57)
	val NavyDark = Color(0xFF071E3D)
	val NavyLight = Color(0xFF1C4A80)
	val Orange = Color(0xFFC2410C)
	val OrangeBright = Color(0xFFF97316)
	val OrangeSoft = Color(0xFFFFF1E6)
	val Background = Color(0xFFF3F5F8)
	val Paper = Color(0xFFFFFFFF)
	val Text = Color(0xFF14213D)
	val TextSecondary = Color(0xFF4A5568)
	val Border = Color(0xFFD9DEE7)
	val Danger = Color(0xFFB42318)
	val DangerSoft = Color(0xFFFEECEB)
	val Success = Color(0xFF1E6B3A)
	val Warning = Color(0xFF8A4B00)
}

private val LightColors = lightColorScheme(
	primary = GksPalette.Navy,
	onPrimary = Color.White,
	primaryContainer = Color(0xFFDCE6F3),
	onPrimaryContainer = GksPalette.NavyDark,
	secondary = GksPalette.Orange,
	onSecondary = Color.White,
	secondaryContainer = GksPalette.OrangeSoft,
	onSecondaryContainer = GksPalette.NavyDark,
	tertiary = GksPalette.NavyLight,
	onTertiary = Color.White,
	error = GksPalette.Danger,
	onError = Color.White,
	errorContainer = GksPalette.DangerSoft,
	onErrorContainer = GksPalette.Danger,
	background = GksPalette.Background,
	onBackground = GksPalette.Text,
	surface = GksPalette.Paper,
	onSurface = GksPalette.Text,
	surfaceVariant = Color(0xFFEEF2F7),
	onSurfaceVariant = GksPalette.TextSecondary,
	outline = Color(0xFF8A94A6),
	outlineVariant = GksPalette.Border,
)

private val DarkColors = darkColorScheme(
	primary = Color(0xFFA9C7F0),
	onPrimary = GksPalette.NavyDark,
	primaryContainer = GksPalette.NavyLight,
	onPrimaryContainer = Color.White,
	secondary = Color(0xFFFFB68A),
	onSecondary = Color(0xFF4A1800),
	secondaryContainer = Color(0xFF7A2A05),
	onSecondaryContainer = Color(0xFFFFDBCA),
	background = Color(0xFF0A1424),
	onBackground = Color(0xFFE4E9F1),
	surface = Color(0xFF111D31),
	onSurface = Color(0xFFE4E9F1),
	error = Color(0xFFFFB4AB),
	onError = Color(0xFF690005),
)

private val GksTypography = Typography().let { base ->
	base.copy(
		headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
		headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
		headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
		titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
		titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
		bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
		bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
		bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
		labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
	)
}

private val GksShapes = Shapes(
	extraSmall = RoundedCornerShape(8.dp),
	small = RoundedCornerShape(10.dp),
	medium = RoundedCornerShape(16.dp),
	large = RoundedCornerShape(20.dp),
	extraLarge = RoundedCornerShape(28.dp),
)

/** Material 3 teması (marka renkleri; açık/koyu). */
@Composable
fun GksTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = if (darkTheme) DarkColors else LightColors,
		typography = GksTypography,
		shapes = GksShapes,
		content = content,
	)
}

/** WCAG kontrast oranı (testler ve denetim için). */
fun contrastRatio(foreground: Color, background: Color): Double {
	fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
	fun lum(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
	val a = lum(foreground)
	val b = lum(background)
	return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
