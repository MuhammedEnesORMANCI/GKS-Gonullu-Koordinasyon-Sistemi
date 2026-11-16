package org.gks.android.ui.theme

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gks.android.R

/**
 * Kurum logosu: `res/drawable/<brand_logo_drawable>` çalışma anında aranır (dosya proje sahibince konur); yoksa
 * kısa ad metni yer tutucu olarak gösterilir. Logo çizilmez.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun BrandLogo(height: Dp = 40.dp, onDark: Boolean = false, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val name = stringResource(R.string.brand_logo_drawable)
	val short = stringResource(R.string.brand_short_name)
	val id = remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
	if (id != 0) {
		Image(painterResource(id), contentDescription = "$short logosu", modifier = modifier.height(height))
	}
	else {
		val color = if (onDark) Color.White else MaterialTheme.colorScheme.primary
		Box(
			modifier
				.height(height)
				.border(2.dp, color, RoundedCornerShape(8.dp))
				.padding(horizontal = 10.dp)
				.semantics { contentDescription = "$short logosu" },
			contentAlignment = Alignment.Center,
		) {
			Text(short, color = color, fontWeight = FontWeight.Black, fontSize = (height.value * 0.42f).sp)
		}
	}
}
