package org.gks.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.gks.android.R
import org.gks.android.data.guide.Hazard
import org.gks.android.ui.guide.EmergencyButton
import org.gks.android.ui.guide.guideIcon
import org.gks.android.ui.theme.BrandLogo
import org.gks.android.ui.theme.GksPalette

/** Hızlı Erişim satırı. */
data class QuickLink(val label: String, val icon: Painter, val badge: Int = 0, val onClick: () -> Unit)

/**
 * Ana sayfa üst bölümü (ADR-023): lacivert başlık (logo + sistem adı), "Afet Anında Ne Yapmalı?" turuncu düğmesi,
 * afet türü kartları ve Hızlı Erişim. Açık acil alarm bandı kabukta, bunun da üstündedir.
 */
@Composable
fun HomeLanding(hazards: List<Hazard>, onHazard: (String) -> Unit, onEmergency: () -> Unit, quickLinks: List<QuickLink>) {
	Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
		Box(
			Modifier
				.fillMaxWidth()
				.background(GksPalette.Navy, RoundedCornerShape(20.dp))
				.padding(16.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				BrandLogo(height = 40.dp, onDark = true)
				Column(Modifier.weight(1f)) {
					Text(stringResource(R.string.brand_system_name), color = Color.White, style = MaterialTheme.typography.titleMedium)
					Text(stringResource(R.string.brand_organization), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
				}
			}
		}
		EmergencyButton(onEmergency)
		Text(stringResource(R.string.guide_hazards), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
		hazards.chunked(2).forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
				row.forEach { hazard ->
					Card(Modifier.weight(1f).clickable { onHazard(hazard.slug) }) {
						Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
							Box(Modifier.background(GksPalette.OrangeSoft, RoundedCornerShape(12.dp)).padding(6.dp)) {
								Icon(guideIcon(hazard.icon), contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(30.dp))
							}
							Text(hazard.title, style = MaterialTheme.typography.titleMedium)
						}
					}
				}
				if (row.size == 1) Box(Modifier.weight(1f))
			}
		}
		Text(stringResource(R.string.home_quick_access), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
		Card(Modifier.fillMaxWidth()) {
			quickLinks.forEachIndexed { index, link ->
				if (index > 0) HorizontalDivider()
				Row(
					Modifier.fillMaxWidth().clickable(onClick = link.onClick).padding(horizontal = 16.dp, vertical = 14.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Icon(link.icon, contentDescription = null, tint = GksPalette.NavyLight, modifier = Modifier.size(26.dp))
					Text(link.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
					if (link.badge > 0) Badge { Text(if (link.badge > 99) "99+" else link.badge.toString()) }
					Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
				}
			}
		}
	}
}
