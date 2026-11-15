package org.gks.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
	primary = Color(0xFF1F5E8C),
	onPrimary = Color.White,
	secondary = Color(0xFF52606D),
	tertiary = Color(0xFF8A5100),
)

private val DarkColors = darkColorScheme(
	primary = Color(0xFF9DCBF5),
	onPrimary = Color(0xFF00324F),
	secondary = Color(0xFFB9C8D6),
	tertiary = Color(0xFFFFB86B),
)

/** Material 3 teması; Android 12+ cihazlarda sistem (dinamik) renkleri kullanılır. */
@Composable
fun GksTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
	val colors = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			val context = LocalContext.current
			if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
		}
		darkTheme -> DarkColors
		else -> LightColors
	}
	MaterialTheme(colorScheme = colors, content = content)
}
