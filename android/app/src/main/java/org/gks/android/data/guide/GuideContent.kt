package org.gks.android.data.guide

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Gömülü rehber içeriği (ADR-023): web içerik dosyaları (`web/src/content`, JSON) derleme sırasında `assets/guide/` altına kopyalanır;
 * ağ gerekmez. Her dosyanın `_dogrulama` notu: yetkili kaynaklarla doğrulanmalı.
 */
@Serializable
data class Hazard(
	val slug: String,
	val title: String,
	val icon: String,
	val summary: String,
	val before: List<String>,
	val during: List<String>,
	val after: List<String>,
)

@Serializable
data class GuideSection(val id: String, val title: String, val icon: String, val intro: String, val items: List<String>)

@Serializable
data class EmergencyStep(val title: String, val text: String)

@Serializable
data class HazardFile(@SerialName("_dogrulama") val verification: String, val hazards: List<Hazard>)

@Serializable
data class GuideFile(@SerialName("_dogrulama") val verification: String, val title: String, val intro: String, val sections: List<GuideSection>)

@Serializable
data class EmergencyFile(
	@SerialName("_dogrulama") val verification: String,
	val title: String,
	val intro: String,
	val callout: String,
	val steps: List<EmergencyStep>,
)

data class GuideContent(val hazards: HazardFile, val guide: GuideFile, val emergency: EmergencyFile) {

	fun hazard(slug: String?): Hazard? = hazards.hazards.firstOrNull { it.slug == slug }

	fun section(id: String?): GuideSection? = guide.sections.firstOrNull { it.id == id }

	companion object {
		private val json = Json { ignoreUnknownKeys = true }

		fun parse(hazards: String, guide: String, emergency: String) = GuideContent(
			json.decodeFromString(HazardFile.serializer(), hazards),
			json.decodeFromString(GuideFile.serializer(), guide),
			json.decodeFromString(EmergencyFile.serializer(), emergency),
		)

		fun load(assets: AssetManager): GuideContent {
			fun read(name: String) = assets.open("guide/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
			return parse(read("hazards.json"), read("guide.json"), read("emergency.json"))
		}
	}
}
