package org.gks.android.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.gks.android.R
import org.gks.android.data.guide.GuideContent
import org.gks.android.ui.theme.GksPalette

/** Rehber rotaları (oturumlu kabukta ve girişten önceki rehberde ortak). */
object GuideRoutes {
	const val LIST = "guides"
	const val HAZARD = "guide/hazard/{slug}"
	const val SECTION = "guide/section/{id}"
	const val EMERGENCY = "guide/emergency"
	fun hazard(slug: String) = "guide/hazard/$slug"
	fun section(id: String) = "guide/section/$id"
	val all = setOf(HAZARD, SECTION, EMERGENCY)
}

/** Rehber ekranlarını bir NavHost'a ekler. */
fun NavGraphBuilder.guideGraph(content: GuideContent, nav: NavHostController, includeList: Boolean) {
	if (includeList) {
		composable(GuideRoutes.LIST) {
			GuidesScreen(
				content,
				onHazard = { nav.navigate(GuideRoutes.hazard(it)) },
				onSection = { nav.navigate(GuideRoutes.section(it)) },
				onEmergency = { nav.navigate(GuideRoutes.EMERGENCY) },
			)
		}
	}
	composable(GuideRoutes.HAZARD) { entry ->
		HazardScreen(content.hazard(entry.arguments?.getString("slug")), content.guide.sections) { nav.navigate(GuideRoutes.section(it)) }
	}
	composable(GuideRoutes.SECTION) { entry -> GuideSectionScreen(content.section(entry.arguments?.getString("id"))) }
	composable(GuideRoutes.EMERGENCY) { EmergencyScreen(content.emergency) }
}

/**
 * Girişten önce açılan rehber (ADR-023): oturum ve ağ gerektirmez; afette ağ olmayabilir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideHost(content: GuideContent, onClose: () -> Unit) {
	val nav = rememberNavController()
	val entry by nav.currentBackStackEntryAsState()
	val atRoot = entry?.destination?.route == GuideRoutes.LIST
	BackHandler(enabled = atRoot, onBack = onClose)
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.guide_title_offline)) },
				navigationIcon = {
					IconButton(onClick = { if (atRoot) onClose() else nav.popBackStack() }) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = GksPalette.Navy,
					titleContentColor = Color.White,
					navigationIconContentColor = Color.White,
				),
			)
		},
	) { padding ->
		Box(Modifier.padding(padding)) {
			NavHost(nav, startDestination = GuideRoutes.LIST) { guideGraph(content, nav, includeList = true) }
		}
	}
}
