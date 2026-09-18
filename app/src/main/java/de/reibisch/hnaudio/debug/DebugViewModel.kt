package de.reibisch.hnaudio.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import de.reibisch.hnaudio.tts.SpeechException
import de.reibisch.hnaudio.tts.SpeechRenderer
import de.reibisch.hnaudio.tts.TtsFiles
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.reibisch.hnaudio.data.ArticleExtractor
import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.Story
import de.reibisch.hnaudio.summary.StorySummaries
import de.reibisch.hnaudio.summary.SummaryException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

data class DebugRow(
    val rank: Int,
    val story: Story,
    val extraction: Extraction? = null,
    val summary: String? = null,
    val summaryError: String? = null,
)

/** One line of what the test player is doing, shown at the top of the debug screen. */
data class PlayerStatus(val storyId: Long, val text: String)

data class DebugState(
    val loading: Boolean = false,
    val error: String? = null,
    val rows: List<DebugRow> = emptyList(),
    val player: PlayerStatus? = null,
)

class DebugViewModel(
    private val hn: HnClient,
    private val extractor: ArticleExtractor,
    private val summaries: StorySummaries,
    private val speech: SpeechRenderer,
    private val ttsFiles: TtsFiles,
    context: Context,
) : ViewModel() {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var playing: List<File> = emptyList()
    private var playJob: Job? = null
    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { DebugState(loading = true, player = it.player) }
            val stories = try {
                hn.topStories(limit = 10)
            } catch (e: IOException) {
                Log.w(TAG, "loading top stories failed: $e")
                _state.value = DebugState(error = e.message ?: "network error")
                return@launch
            }
            _state.update { it.copy(rows = stories.mapIndexed { i, s -> DebugRow(i + 1, s) }) }
            val fetchPermits = Semaphore(4)
            // Low concurrency keeps us under the free tier's per-minute limit.
            val summaryPermits = Semaphore(2)
            stories.mapIndexed { i, story ->
                launch {
                    val result = fetchPermits.withPermit { extractor.extract(story.url) }
                    Log.i(TAG, "#${i + 1} ${story.title} [${story.domain ?: "text"}] -> ${describe(result)}")
                    updateRow(story.id) { it.copy(extraction = result) }
                    try {
                        val summary = summaryPermits.withPermit { summaries.summaryFor(story, result) }
                        updateRow(story.id) { it.copy(summary = summary.text) }
                    } catch (e: SummaryException) {
                        Log.w(TAG, "#${i + 1} summary failed: ${e.message}")
                        updateRow(story.id) { it.copy(summaryError = e.message) }
                    } catch (e: IOException) {
                        updateRow(story.id) { it.copy(summaryError = "network error: ${e.message}") }
                    }
                }
            }.forEach { it.join() }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Phase 4 test: render the spoken intro plus summary and play it. */
    fun play(row: DebugRow) {
        val summary = row.summary ?: return
        val story = row.story
        playJob?.cancel()
        player.stop()
        playing.forEach(ttsFiles::delete)
        playing = emptyList()
        playJob = viewModelScope.launch {
            setPlayer(story.id, "Rendering…")
            val text = "Story ${row.rank}. ${story.title}. " +
                "${story.score} points, ${story.commentCount} comments.\n\n$summary"
            val started = System.currentTimeMillis()
            val files = try {
                speech.render(text)
            } catch (e: SpeechException) {
                setPlayer(story.id, "Speech failed: ${e.message}")
                return@launch
            }
            val ms = System.currentTimeMillis() - started
            Log.i(TAG, "Rendered ${text.length} chars into ${files.size} file(s) in ${ms} ms")
            playing = files
            player.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            player.prepare()
            player.play()
            setPlayer(story.id, "Playing (rendered in ${ms} ms, ${files.size} file(s))")
        }
    }

    fun stop() {
        playJob?.cancel()
        player.stop()
        _state.update { it.copy(player = null) }
    }

    override fun onCleared() {
        player.release()
        playing.forEach(ttsFiles::delete)
    }

    private fun setPlayer(storyId: Long, text: String) {
        _state.update { it.copy(player = PlayerStatus(storyId, text)) }
    }

    private fun updateRow(id: Long, change: (DebugRow) -> DebugRow) {
        _state.update { s -> s.copy(rows = s.rows.map { if (it.story.id == id) change(it) else it }) }
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
