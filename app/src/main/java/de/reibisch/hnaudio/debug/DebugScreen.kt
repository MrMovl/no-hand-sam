package de.reibisch.hnaudio.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.reibisch.hnaudio.data.Extraction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: DebugViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Top stories (debug)") },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.story.id }) { row -> StoryRow(row) }
            }
        }
    }
}

@Composable
private fun StoryRow(row: DebugRow) {
    val story = row.story
    Column {
        Text("${row.rank}. ${story.title}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${story.domain ?: "text post"} · ${story.score} points · ${story.commentCount} comments",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            DebugViewModel.describe(row.extraction),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.extraction is Extraction.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        row.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        row.summaryError?.let {
            Text("Summary failed: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
