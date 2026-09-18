package de.reibisch.hnaudio.debug

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.reibisch.hnaudio.data.ArticleExtractor
import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.Story
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

data class DebugRow(val rank: Int, val story: Story, val extraction: Extraction? = null)

data class DebugState(
    val loading: Boolean = false,
    val error: String? = null,
    val rows: List<DebugRow> = emptyList(),
)

class DebugViewModel(
    private val hn: HnClient,
    private val extractor: ArticleExtractor,
) : ViewModel() {
    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = DebugState(loading = true)
            val stories = try {
                hn.topStories(limit = 10)
            } catch (e: IOException) {
                Log.w(TAG, "loading top stories failed: $e")
                _state.value = DebugState(error = e.message ?: "network error")
                return@launch
            }
            _state.value = DebugState(
                loading = true,
                rows = stories.mapIndexed { i, s -> DebugRow(i + 1, s) },
            )
            val permits = Semaphore(4)
            stories.mapIndexed { i, story ->
                launch {
                    val result = permits.withPermit { extractor.extract(story.url) }
                    Log.i(TAG, "#${i + 1} ${story.title} [${story.domain ?: "text"}] -> ${describe(result)}")
                    _state.update { s ->
                        s.copy(rows = s.rows.map { if (it.story.id == story.id) it.copy(extraction = result) else it })
                    }
                }
            }.forEach { it.join() }
            _state.update { it.copy(loading = false) }
        }
    }

    companion object {
        const val TAG = "HnDebug"

        fun describe(e: Extraction?): String = when (e) {
            null -> "extracting…"
            is Extraction.Article ->
                "${e.wordCount} words, ${e.paragraphs.size} paragraphs" +
                    if (e.source == Extraction.Source.GitHubReadme) " (README)" else ""
            is Extraction.Failed -> "failed: ${e.reason.description}" + (e.detail?.let { " ($it)" } ?: "")
        }
    }
}
