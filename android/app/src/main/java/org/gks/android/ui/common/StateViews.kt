package org.gks.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.gks.android.R

@Composable
fun LoadingView(@StringRes label: Int = R.string.loading) {
	Centered {
		CircularProgressIndicator()
		Spacer(Modifier.height(16.dp))
		Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
fun ErrorView(message: UiText, onRetry: () -> Unit) {
	Centered {
		Text(stringResource(R.string.load_error_title), style = MaterialTheme.typography.titleMedium)
		Spacer(Modifier.height(8.dp))
		Text(message.resolve(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Spacer(Modifier.height(24.dp))
		Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
	}
}

@Composable
fun EmptyView(@StringRes title: Int, @StringRes body: Int? = null) {
	Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
		if (body != null) {
			Spacer(Modifier.height(8.dp))
			Text(
				stringResource(body),
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
		content = content,
	)
}

/**
 * [Load] durumuna göre yükleniyor / hata / içerik. İçerik kaydırılabilir listedir; yenilemede üstte ince ilerleme
 * çubuğu, yenileme hatasında bant gösterilir.
 */
@Composable
fun <T> LoadContent(state: Load<T>, onRetry: () -> Unit, content: LazyListScope.(T) -> Unit) {
	when (state) {
		Load.Loading -> LoadingView()
		is Load.Failed -> ErrorView(state.message, onRetry)
		is Load.Ready -> Column(Modifier.fillMaxSize()) {
			if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
			LazyColumn(
				contentPadding = PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				state.error?.let { error -> item { MessageCard(error.resolve(), error = true) } }
				content(state.value)
			}
		}
	}
}

@Composable
fun MessageCard(text: String, error: Boolean) {
	val colors = if (error) {
		CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
			contentColor = MaterialTheme.colorScheme.onErrorContainer,
		)
	} else {
		CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.secondaryContainer,
			contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
		)
	}
	Card(colors = colors, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
		Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
	}
}

@Composable
fun SectionTitle(@StringRes title: Int) {
	Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun InfoCard(@StringRes title: Int, @StringRes body: Int) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp)) {
			Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
			Spacer(Modifier.height(8.dp))
			Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
		}
	}
}

/** Etiket + değer satırı (ayrıntı ekranları). */
@Composable
fun Fact(@StringRes label: Int, value: String) {
	Column {
		Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Spacer(Modifier.height(2.dp))
		Text(value, style = MaterialTheme.typography.bodyLarge)
	}
}
