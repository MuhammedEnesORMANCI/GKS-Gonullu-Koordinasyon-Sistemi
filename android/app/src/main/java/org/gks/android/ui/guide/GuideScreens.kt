package org.gks.android.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.gks.android.R
import org.gks.android.data.guide.EmergencyFile
import org.gks.android.data.guide.GuideContent
import org.gks.android.data.guide.GuideSection
import org.gks.android.data.guide.Hazard
import org.gks.android.ui.theme.GksPalette

/** Gömülü içerik ikonları (web `features/public/icons.tsx` ile aynı anahtarlar). */
@Composable
fun guideIcon(key: String): Painter = when (key) {
	"earthquake" -> painterResource(R.drawable.ic_hazard_earthquake)
	"flood" -> painterResource(R.drawable.ic_hazard_flood)
	"landslide" -> painterResource(R.drawable.ic_hazard_landslide)
	"fire" -> painterResource(R.drawable.ic_hazard_fire)
	"winter" -> painterResource(R.drawable.ic_hazard_winter)
	"bag" -> painterResource(R.drawable.ic_guide_bag)
	"family" -> painterResource(R.drawable.ic_guide_family)
	"place" -> rememberVectorPainter(Icons.Filled.Place)
	else -> rememberVectorPainter(Icons.Filled.Warning)
}

/** "Afet Anında Ne Yapmalı?" turuncu düğmesi (ana sayfa ve rehberler). */
@Composable
fun EmergencyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
	Button(
		onClick = onClick,
		colors = ButtonDefaults.buttonColors(containerColor = GksPalette.Orange, contentColor = Color.White),
		shape = RoundedCornerShape(16.dp),
		modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
	) {
		Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(28.dp))
		Text(stringResource(R.string.guide_what_to_do), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
	}
}

/** Afet türü kartları (iki sütun). */
fun LazyListScope.hazardCards(hazards: List<Hazard>, onHazard: (String) -> Unit) {
	items(hazards.chunked(2), key = { row -> "hz-" + row.first().slug }) { row ->
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
			row.forEach { hazard -> HazardCard(hazard, onClick = { onHazard(hazard.slug) }, modifier = Modifier.weight(1f)) }
			if (row.size == 1) Box(Modifier.weight(1f))
		}
	}
}

@Composable
private fun HazardCard(hazard: Hazard, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Card(
		modifier = modifier.heightIn(min = 112.dp).clickable(onClick = onClick),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
		elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
	) {
		Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Box(Modifier.background(GksPalette.OrangeSoft, RoundedCornerShape(12.dp)).padding(8.dp)) {
				Icon(guideIcon(hazard.icon), contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(36.dp))
			}
			Text(hazard.title, style = MaterialTheme.typography.titleMedium)
		}
	}
}

@Composable
fun SectionHeading(text: String) {
	Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
fun VerificationNote() {
	Text(
		stringResource(R.string.guide_offline_note),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/** Rehberler sekmesi: ne yapmalı, afet türleri, hazırlık bölümleri (internetsiz). */
@Composable
fun GuidesScreen(content: GuideContent, onHazard: (String) -> Unit, onSection: (String) -> Unit, onEmergency: () -> Unit) {
	LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
		item { EmergencyButton(onEmergency) }
		item { SectionHeading(stringResource(R.string.guide_hazards)) }
		hazardCards(content.hazards.hazards, onHazard)
		item { SectionHeading(content.guide.title) }
		items(content.guide.sections, key = { "gs-" + it.id }) { section -> SectionRow(section, onClick = { onSection(section.id) }) }
		item { VerificationNote() }
	}
}

@Composable
fun SectionRow(section: GuideSection, onClick: () -> Unit) {
	Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
			Icon(guideIcon(section.icon), contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(32.dp))
			Column(Modifier.weight(1f)) {
				Text(section.title, style = MaterialTheme.typography.titleMedium)
				Text(section.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
			}
			Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
		}
	}
}

/** Afet türü: öncesinde / sırasında / sonrasında. */
@Composable
fun HazardScreen(hazard: Hazard?, sections: List<GuideSection>, onSection: (String) -> Unit) {
	if (hazard == null) {
		Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.guide_not_found)) }
		return
	}
	LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
		item {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Icon(guideIcon(hazard.icon), contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(44.dp))
				Column {
					Text(hazard.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
					Text(hazard.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
		item { PhaseCard(stringResource(R.string.guide_before), hazard.before, GksPalette.NavyLight) }
		item { PhaseCard(stringResource(R.string.guide_during), hazard.during, GksPalette.Orange) }
		item { PhaseCard(stringResource(R.string.guide_after), hazard.after, GksPalette.Success) }
		item { SectionHeading(stringResource(R.string.guide_preparedness)) }
		items(sections, key = { "hs-" + it.id }) { section -> SectionRow(section, onClick = { onSection(section.id) }) }
		item { VerificationNote() }
	}
}

@Composable
private fun PhaseCard(title: String, items: List<String>, accent: Color) {
	Card(Modifier.fillMaxWidth()) {
		Column {
			Box(Modifier.fillMaxWidth().background(accent).padding(horizontal = 16.dp, vertical = 10.dp)) {
				Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
			}
			Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				items.forEach { BulletRow(it) }
			}
		}
	}
}

@Composable
private fun BulletRow(text: String) {
	Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GksPalette.Success, modifier = Modifier.size(20.dp).padding(top = 2.dp))
		Text(text, style = MaterialTheme.typography.bodyLarge)
	}
}

/** Hazırlık bölümü (acil durum çantası, aile afet planı, toplanma alanı). */
@Composable
fun GuideSectionScreen(section: GuideSection?) {
	if (section == null) {
		Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.guide_not_found)) }
		return
	}
	LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
		item {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Icon(guideIcon(section.icon), contentDescription = null, tint = GksPalette.Orange, modifier = Modifier.size(44.dp))
				Text(section.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
			}
		}
		item { Text(section.intro, style = MaterialTheme.typography.bodyLarge) }
		item {
			Card(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { section.items.forEach { BulletRow(it) } }
			}
		}
		item { VerificationNote() }
	}
}

/** "Afet Anında Ne Yapmalı?": 112 vurgusu ve numaralı adımlar. */
@Composable
fun EmergencyScreen(emergency: EmergencyFile) {
	LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
		item { Text(emergency.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
		item { Text(emergency.intro, style = MaterialTheme.typography.bodyLarge) }
		item {
			Card(colors = CardDefaults.cardColors(containerColor = GksPalette.Danger, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) {
				Text(emergency.callout, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
			}
		}
		items(emergency.steps.withIndex().toList(), key = { "es-" + it.index }) { (index, step) ->
			Card(Modifier.fillMaxWidth()) {
				Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
					Box(Modifier.size(40.dp).background(GksPalette.Orange, CircleShape), contentAlignment = Alignment.Center) {
						Text((index + 1).toString(), color = Color.White, fontWeight = FontWeight.ExtraBold)
					}
					Column {
						Text(step.title, style = MaterialTheme.typography.titleMedium)
						Text(step.text, style = MaterialTheme.typography.bodyLarge)
					}
				}
			}
		}
		item { VerificationNote() }
	}
}
