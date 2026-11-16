package org.gks.android.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.remote.LastLocation
import org.gks.android.data.remote.MapOperationView
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.StatusBadge
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.common.UiText

/** ADM/KOR Operasyon Haritası (ADR-025): ADM tümü, KOR kendi operasyonları; Planlananlar isteğe bağlı. */
class OperationsMapViewModel(private val repository: ManagementRepository) : LoadViewModel<List<MapOperationView>>() {
	private val _planned = MutableStateFlow(false)
	val planned: StateFlow<Boolean> = _planned.asStateFlow()

	init { load() }

	override suspend fun fetch() = repository.mapOperations(_planned.value)

	fun togglePlanned() {
		_planned.value = !_planned.value
		load()
	}
}

@Composable
fun OperationsMapScreen(viewModel: OperationsMapViewModel, onOpen: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val planned by viewModel.planned.collectAsStateWithLifecycle()
	var selected by rememberSaveable { mutableStateOf<String?>(null) }
	LoadContent(state, onRetry = viewModel::load) { items ->
		item {
			FilterChip(selected = planned, onClick = viewModel::togglePlanned, label = { Text(stringResource(R.string.map_include_planned)) })
		}
		item {
			GksMap(
				points = operationPoints(items),
				circles = operationCircles(items),
				height = 320.dp,
				description = stringResource(R.string.map_title),
				onPointClick = { selected = it },
			)
		}
		items.firstOrNull { it.id == selected }?.let { op -> item(key = "summary") { MapSummaryCard(op, onOpen) } }
		if (items.isEmpty()) item { EmptyView(R.string.map_empty) }
		items(items, key = { it.id }) { op ->
			val anchor = op.anchor()
			ElevatedCard(Modifier.fillMaxWidth().clickable { selected = op.id }) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					TitleRow(op.name) { StatusBadge(StatusKind.OPERATION, op.state) }
					Text(
						(if (op.openAlert != null) stringResource(R.string.map_alert_short) + " · " else "") +
							(anchor?.let { formatLatLon(it.first, it.second) } ?: stringResource(R.string.map_no_location)),
						style = MaterialTheme.typography.bodySmall,
						color = if (op.openAlert != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
fun MapSummaryCard(op: MapOperationView, onOpen: (String) -> Unit) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			TitleRow(op.name) { StatusBadge(StatusKind.OPERATION, op.state) }
			Text(stringResource(R.string.map_participants, op.participantCount))
			val alert = op.openAlert
			if (alert != null) {
				Text(
					stringResource(R.string.map_open_alert, alert.counts.responded, alert.counts.total, alert.counts.pending, alert.counts.needHelp),
					color = MaterialTheme.colorScheme.error,
				)
			} else {
				Text(stringResource(R.string.map_no_alert), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Button(onClick = { onOpen(op.id) }) { Text(stringResource(R.string.map_go_operation)) }
		}
	}
}

/** Operasyon yönetim ekranındaki harita: bölge, buluşma noktaları; son konumlar yalnızca istenince (denetimli). */
class OperationMapViewModel(private val repository: ManagementRepository, private val operationId: String) : ViewModel() {
	private val _map = MutableStateFlow<Load<MapOperationView>>(Load.Loading)
	val map: StateFlow<Load<MapOperationView>> = _map.asStateFlow()
	private val _people = MutableStateFlow<List<LastLocation>?>(null)
	val people: StateFlow<List<LastLocation>?> = _people.asStateFlow()
	private val _error = MutableStateFlow<UiText?>(null)
	val error: StateFlow<UiText?> = _error.asStateFlow()

	init { load() }

	fun load() {
		viewModelScope.launch {
			_map.value = when (val r = repository.mapOperation(operationId)) {
				is ApiResult.Success -> Load.Ready(r.value)
				is ApiResult.Failure -> Load.Failed(r.error.toUiText())
			}
		}
	}

	fun setShowPeople(show: Boolean) {
		if (!show) {
			_people.value = null
			return
		}
		viewModelScope.launch {
			when (val r = repository.lastLocations(operationId)) {
				is ApiResult.Success -> { _people.value = r.value.items; _error.value = null }
				is ApiResult.Failure -> _error.value = r.error.toUiText()
			}
		}
	}
}

@Composable
fun OperationMapSection(viewModel: OperationMapViewModel, canSeeLocations: Boolean) {
	val state by viewModel.map.collectAsStateWithLifecycle()
	val people by viewModel.people.collectAsStateWithLifecycle()
	val error by viewModel.error.collectAsStateWithLifecycle()
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.map_operation_title), style = MaterialTheme.typography.titleMedium)
			when (val s = state) {
				Load.Loading -> LoadingView()
				is Load.Failed -> TextButton(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
				is Load.Ready -> {
					val op = s.value
					val points = buildList {
						op.area?.let { add(MapPoint("center", it.lat, it.lon, MapColors.forState(op.state, op.openAlert != null), op.name)) }
						op.meetingPoints.forEach { add(MapPoint("mp-${it.groupId}", it.lat, it.lon, MapColors.MEETING_POINT, it.groupName)) }
						people.orEmpty().forEach { add(MapPoint("u-${it.user.id}", it.lat, it.lon, MapColors.PERSON, it.user.displayName)) }
					}
					val circles = op.area?.radiusM?.let { listOf(MapCircle("area", op.area.lat, op.area.lon, it, MapColors.ACTIVE)) }.orEmpty()
					GksMap(points, circles, description = stringResource(R.string.map_operation_title))
					Text(
						stringResource(R.string.map_area) + ": " + (op.area?.let { a ->
							formatLatLon(a.lat, a.lon) + (a.radiusM?.let { " · " + stringResource(R.string.map_radius, it) } ?: "")
						} ?: stringResource(R.string.map_area_none)),
						style = MaterialTheme.typography.bodyMedium,
					)
					op.meetingPoints.forEach { mp ->
						Text(stringResource(R.string.map_meeting_point, mp.groupName) + ": " + listOfNotNull(mp.address, formatLatLon(mp.lat, mp.lon)).joinToString(" · "),
							style = MaterialTheme.typography.bodySmall)
					}
				}
			}
			if (canSeeLocations) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(checked = people != null, onCheckedChange = viewModel::setShowPeople)
					Text(stringResource(R.string.map_show_people), style = MaterialTheme.typography.bodyMedium)
				}
				people?.let { Text(stringResource(R.string.map_people_count, it.size), style = MaterialTheme.typography.bodySmall) }
				error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
			}
		}
	}
}

/** Gönüllü: buluşma noktası haritada + kendi konumu; "Yol tarifi" `geo:` ile (çevrimdışı da çalışır). */
@Composable
fun MeetingPointMap(lat: Double, lon: Double, label: String) {
	val context = LocalContext.current
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		GksMap(
			points = listOf(MapPoint("mp", lat, lon, MapColors.MEETING_POINT, label)),
			height = 200.dp,
			description = stringResource(R.string.map_meeting_point, label),
			showMyLocation = true,
		)
		Text(stringResource(R.string.meeting_point_coords) + ": " + formatLatLon(lat, lon), style = MaterialTheme.typography.bodyMedium)
		OutlinedButton(onClick = { openDirections(context, lat, lon, label) }) { Text(stringResource(R.string.map_directions)) }
	}
}

/** Operasyon formundaki bölge seçimi: haritaya dokunarak ya da elle; yarıçap isteğe bağlı. */
@Composable
fun AreaFields(lat: String, lon: String, radius: String, errors: Map<String, AreaError>, enabled: Boolean,
		onChange: (String, String, String) -> Unit) {
	val parsed = remember(lat, lon, radius) { parseArea(lat, lon, radius) }
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(stringResource(R.string.area_title), style = MaterialTheme.typography.titleMedium)
		val area = parsed.area
		GksMap(
			points = area?.let { listOf(MapPoint("center", it.lat, it.lon, MapColors.ACTIVE, "")) }.orEmpty(),
			circles = area?.radiusM?.let { listOf(MapCircle("area", area.lat, area.lon, it, MapColors.ACTIVE)) }.orEmpty(),
			height = 220.dp,
			description = stringResource(R.string.area_title),
			onPick = { la, lo -> if (enabled) onChange(coord(la), coord(lo), radius) },
		)
		OutlinedTextField(lat, { onChange(it, lon, radius) }, label = { Text(stringResource(R.string.area_lat)) }, enabled = enabled,
			isError = errors["area.lat"] != null, supportingText = errors["area.lat"]?.let { { Text(stringResource(areaErrorText(it))) } },
			modifier = Modifier.fillMaxWidth())
		OutlinedTextField(lon, { onChange(lat, it, radius) }, label = { Text(stringResource(R.string.area_lon)) }, enabled = enabled,
			isError = errors["area.lon"] != null, supportingText = errors["area.lon"]?.let { { Text(stringResource(areaErrorText(it))) } },
			modifier = Modifier.fillMaxWidth())
		OutlinedTextField(radius, { onChange(lat, lon, it) }, label = { Text(stringResource(R.string.area_radius)) }, enabled = enabled,
			isError = errors["area.radiusM"] != null,
			supportingText = { Text(errors["area.radiusM"]?.let { stringResource(areaErrorText(it)) } ?: stringResource(R.string.area_radius_help)) },
			modifier = Modifier.fillMaxWidth())
		if (lat.isNotBlank() || lon.isNotBlank() || radius.isNotBlank()) {
			TextButton(onClick = { onChange("", "", "") }, enabled = enabled) { Text(stringResource(R.string.area_clear)) }
		}
	}
}

fun areaErrorText(error: AreaError): Int = when (error) {
	AreaError.BOTH_REQUIRED -> R.string.area_err_both
	AreaError.LAT_RANGE -> R.string.area_err_lat
	AreaError.LON_RANGE -> R.string.area_err_lon
	AreaError.RADIUS_RANGE -> R.string.area_err_radius
	AreaError.RADIUS_WITHOUT_CENTER -> R.string.area_err_radius_center
}
