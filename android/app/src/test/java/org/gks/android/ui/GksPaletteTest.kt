package org.gks.android.ui

import androidx.compose.ui.graphics.Color
import org.gks.android.ui.theme.GksPalette
import org.gks.android.ui.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasarım sistemi: metin/zemin çiftleri WCAG AA (≥ 4.5:1), web temasıyla aynı palet. */
class GksPaletteTest {
	@Test
	fun textAndButtonPairsMeetWcagAa() {
		val pairs = listOf(
			"gövde" to (GksPalette.Text to GksPalette.Paper),
			"gövde gri" to (GksPalette.Text to GksPalette.Background),
			"ikincil" to (GksPalette.TextSecondary to GksPalette.Paper),
			"ikincil gri" to (GksPalette.TextSecondary to GksPalette.Background),
			"lacivert düğme" to (Color.White to GksPalette.Navy),
			"turuncu düğme" to (Color.White to GksPalette.Orange),
			"tehlike" to (Color.White to GksPalette.Danger),
			"tehlike açık" to (GksPalette.Danger to GksPalette.DangerSoft),
			"başarı" to (Color.White to GksPalette.Success),
			"uyarı" to (Color.White to GksPalette.Warning),
			"turuncu açık" to (GksPalette.NavyDark to GksPalette.OrangeSoft),
		)
		for ((name, pair) in pairs) {
			val ratio = contrastRatio(pair.first, pair.second)
			assertTrue("$name: $ratio", ratio >= 4.5)
		}
	}
}
