package org.gks.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.gks.android.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tarih-saat alanı: dokununca önce tarih, ardından saat (24 saat) seçtirir; değer UTC ISO metni olarak döner
 * ([DateTimes.toIso]). [clearable] ise isteğe bağlı alan temizlenebilir (`onChange(null)`). Hata metni (istemci veya
 * sunucunun 400 alan hatası) alanın altında gösterilir.
 *
 * @param minDate seçilebilecek en erken yerel tarih (ör. planlanan bitiş için bugün); `null` sınırsız
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
	valueIso: String?,
	@StringRes label: Int,
	error: String?,
	enabled: Boolean,
	onChange: (String?) -> Unit,
	@StringRes supporting: Int? = null,
	clearable: Boolean = true,
	minDate: LocalDate? = null,
	zone: ZoneId = ZoneId.systemDefault(),
) {
	var pickingDate by remember { mutableStateOf(false) }
	var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

	// Salt-okur alan: dokunma tarih seçiciyi açar (klavye açılmaz).
	val interaction = remember { MutableInteractionSource() }
	LaunchedEffect(interaction, enabled) {
		interaction.interactions.collect { if (enabled && it is PressInteraction.Release) pickingDate = true }
	}
	OutlinedTextField(
		value = DateTimes.display(valueIso, zone),
		onValueChange = {},
		readOnly = true,
		label = { Text(stringResource(label)) },
		placeholder = { Text(stringResource(R.string.datetime_pick)) },
		enabled = enabled,
		isError = error != null,
		singleLine = true,
		interactionSource = interaction,
		supportingText = (error ?: supporting?.let { stringResource(it) })?.let { { Text(it) } },
		trailingIcon = {
			Row {
				if (clearable && valueIso != null) {
					IconButton(onClick = { onChange(null) }, enabled = enabled) {
						Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.datetime_clear))
					}
				}
				IconButton(onClick = { pickingDate = true }, enabled = enabled) {
					Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.datetime_pick))
				}
			}
		},
		modifier = Modifier.fillMaxWidth(),
	)

	if (pickingDate) {
		val initial = DateTimes.initialDate(valueIso, zone, Instant.now()).let { if (minDate != null && it < minDate) minDate else it }
		val minMillis = minDate?.let(DateTimes::dateToPickerMillis)
		val state = rememberDatePickerState(
			initialSelectedDateMillis = DateTimes.dateToPickerMillis(initial),
			selectableDates = object : SelectableDates {
				override fun isSelectableDate(utcTimeMillis: Long) = minMillis == null || utcTimeMillis >= minMillis
				override fun isSelectableYear(year: Int) = minDate == null || year >= minDate.year
			},
		)
		DatePickerDialog(
			onDismissRequest = { pickingDate = false },
			confirmButton = {
				TextButton(
					onClick = {
						pickedDate = state.selectedDateMillis?.let(DateTimes::pickerMillisToDate)
						pickingDate = false
					},
					enabled = state.selectedDateMillis != null,
				) { Text(stringResource(R.string.datetime_next)) }
			},
			dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
		) { DatePicker(state = state) }
	}

	pickedDate?.let { date ->
		val time = DateTimes.initialTime(valueIso, zone, Instant.now())
		val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
		AlertDialog(
			onDismissRequest = { pickedDate = null },
			title = { Text(stringResource(R.string.datetime_pick_time)) },
			text = { TimePicker(state = state) },
			confirmButton = {
				TextButton(onClick = {
					onChange(DateTimes.toIso(date, LocalTime.of(state.hour, state.minute), zone))
					pickedDate = null
				}) { Text(stringResource(R.string.action_confirm)) }
			},
			dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}
