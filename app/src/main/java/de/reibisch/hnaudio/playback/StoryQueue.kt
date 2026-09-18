package de.reibisch.hnaudio.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import de.reibisch.hnaudio.data.ArticleExtractor
import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.FailureReason
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.Story
import de.reibisch.hnaudio.summary.StorySummaries
import de.reibisch.hnaudio.summary.SummaryException
import de.reibisch.hnaudio.tts.SpeechException
import de.reibisch.hnaudio.tts.SpeechRenderer
import de.reibisch.hnaudio.tts.TtsFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Builds the playlist: one summary item per story, prepared a few stories ahead of the
 * listener, plus full-article items inserted on demand. Everything runs on the main thread,
 * like the player itself; slow work suspends inside the data and speech layers.
 */
class StoryQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val hn: HnClient,
    private val extractor: ArticleExtractor,
    private val summaries: StorySummaries,
    private val speech: SpeechRenderer,
    private val ttsFiles: TtsFiles,
    private val speechRate: suspend () -> Float,
) {
    private var stories: List<Story> = emptyList()
    private val extractions = mutableMapOf<Int, Extraction>()

    /** Story the listener is on; `stories.size` once the end cue is reached. */
    private val currentStory = MutableStateFlow(0)

    /** Bumped on every playlist transition so waiting coroutines re-check the player. */
    private val positionTick = MutableStateFlow(0)

    private var prefetchJob: Job? = null
    private var articleJob: Job? = null
    private var articleStory = -1

    var isStarted = false
        private set

    /** True when the story list couldn't be loaded, or the list has been played to the end. */
    val canRestart: Boolean
        get() = player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.kind == ItemKind.END

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.storyIndex?.takeIf { it >= 0 }?.let { currentStory.value = it }
            positionTick.value++
            // The listener only moves forward, so everything before the current item is done.
            scope.launch { removeRange(0, player.currentMediaItemIndex) }
        }
    }

    init {
        player.addListener(listener)
    }

    fun start() {
        prefetchJob?.cancel()
        articleJob?.cancel()
        removeRange(0, player.mediaItemCount)
        stories = emptyList()
        extractions.clear()
        currentStory.value = 0
        isStarted = true
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        prefetchJob = scope.launch { prefetch() }
    }

    /** Next button: leave the current story (summary or article) and go to the next one. */
    fun next() {
        val cur = currentStory.value
        if (!isStarted || cur >= stories.size) return
        articleJob?.cancel()
        currentStory.value = cur + 1
        // Removing the playing item makes the player continue with whatever follows it,
        // or end and wait until the next story has been appended.
        val end = indexOfFirst { it.storyIndex > cur } ?: player.mediaItemCount
        removeRange(0, end)
    }

    /** Previous button: read the full article during a summary, else restart the paragraph. */
    fun previous() {
        val item = player.currentMediaItem ?: return
        when (item.kind) {
            ItemKind.SUMMARY -> readArticle(item.storyIndex)
            else -> player.seekTo(player.currentMediaItemIndex, 0)
        }
    }

    fun release() {
        player.removeListener(listener)
        prefetchJob?.cancel()
        articleJob?.cancel()
        removeRange(0, player.mediaItemCount)
    }

    private suspend fun prefetch() {
        stories = try {
            hn.topStories(STORY_COUNT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading stories failed: $e")
            appendCue("Couldn't load Hacker News. Check your internet connection and press play to try again.")
            return
        }
        Log.i(TAG, "Loaded ${stories.size} stories")
        for (index in stories.indices) {
            currentStory.first { index <= it + PREPARE_AHEAD }
            if (index < currentStory.value) continue // skipped before we got to it
            val files = prepareSummary(index)
            if (index < currentStory.value) {
                files.forEach(ttsFiles::delete)
                continue
            }
            append(files.map { queueItem(it, index, ItemKind.SUMMARY, stories[index], stories.size) })
        }
        appendCue("That's all the stories for now.")
    }

    private suspend fun prepareSummary(index: Int): List<File> {
        val story = stories[index]
        val extraction = extractor.extract(story.url).also { extractions[index] = it }
        val summary = try {
            summaries.summaryFor(story, extraction).text
        } catch (e: SummaryException) {
            Log.w(TAG, "Summary for #${index + 1} failed: ${e.message}")
            "There's no summary for this one."
        } catch (e: IOException) {
            "There's no summary for this one."
        }
        val intro = "Story ${index + 1}. ${story.title}. ${story.score} points, ${story.commentCount} comments."
        return try {
            speech.render("$intro\n\n$summary")
        } catch (e: SpeechException) {
            Log.w(TAG, "Rendering #${index + 1} failed: ${e.message}")
            emptyList()
        }
    }

    private fun readArticle(storyIndex: Int) {
        if (articleJob?.isActive == true && articleStory == storyIndex) return
        val story = stories.getOrNull(storyIndex) ?: return
        articleJob?.cancel()
        articleStory = storyIndex
        articleJob = scope.launch {
            try {
                val extraction = extractions[storyIndex] ?: extractor.extract(story.url)
                val paragraphs = (extraction as? Extraction.Article)?.paragraphs
                    ?: story.text.takeIf { it.isNotEmpty() }
                if (paragraphs == null) {
                    insertAndJump(storyIndex, speech.render(unavailableText(extraction)))
                    return@launch
                }
                val cue = if (extraction is Extraction.Article) {
                    val words = paragraphs.sumOf { p -> p.split(' ').count { it.isNotEmpty() } }
                    val minutes = (words / (WORDS_PER_MINUTE * speechRate())).roundToInt().coerceAtLeast(1)
                    "Reading full article, about $minutes ${if (minutes == 1) "minute" else "minutes"}."
                } else {
                    "Reading the full post."
                }
                insertAndJump(storyIndex, speech.render(cue))
                for (paragraph in paragraphs) {
                    // Stay a few paragraphs ahead; a long article would otherwise be 100+ MB of WAV.
                    positionTick.first { articleItemsAhead(storyIndex) < ARTICLE_AHEAD }
                    val files = speech.render(paragraph)
                    insertAfterStory(storyIndex, files.map { queueItem(it, storyIndex, ItemKind.ARTICLE, story, stories.size) })
                }
            } catch (e: SpeechException) {
                Log.w(TAG, "Rendering article #${storyIndex + 1} failed: ${e.message}")
            }
        }
    }

    private fun unavailableText(extraction: Extraction): String {
        val reason = when ((extraction as? Extraction.Failed)?.reason) {
            FailureReason.NoUrl -> "there's nothing to read beyond the title"
            FailureReason.Pdf -> "it's a PDF"
            FailureReason.Video -> "it's a video"
            FailureReason.Blocked, FailureReason.TooShort -> "it's probably behind a paywall"
            FailureReason.NotHtml -> "it's not a regular web page"
            else -> "the site didn't respond"
        }
        return "Sorry, the full article isn't available, $reason."
    }

    private fun insertAndJump(storyIndex: Int, files: List<File>) {
        if (files.isEmpty()) return
        val story = stories[storyIndex]
        val at = insertAfterStory(storyIndex, files.map { queueItem(it, storyIndex, ItemKind.ARTICLE_CUE, story, stories.size) })
        player.seekTo(at, 0)
    }

    /** Inserts right after the story's last item, before the next story. Returns the index. */
    private fun insertAfterStory(storyIndex: Int, items: List<MediaItem>): Int {
        val at = (lastIndexOf { it.storyIndex == storyIndex } ?: (player.currentMediaItemIndex)) + 1
        player.addMediaItems(at, items)
        return at
    }

    private fun appendCue(text: String) {
        scope.launch {
            val files = try {
                speech.render(text)
            } catch (e: SpeechException) {
                return@launch
            }
            append(files.map { queueItem(it, stories.size, ItemKind.END, null, stories.size) })
        }
    }

    /** Appends at the end; if the player ran out of items while waiting, continue with these. */
    private fun append(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val waiting = player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0
        val first = player.mediaItemCount
        player.addMediaItems(items)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (waiting) player.seekTo(first, 0)
    }

    private fun articleItemsAhead(storyIndex: Int): Int =
        ((player.currentMediaItemIndex + 1) until player.mediaItemCount)
            .count { player.getMediaItemAt(it).storyIndex == storyIndex }

    private fun removeRange(from: Int, to: Int) {
        if (to <= from) return
        val files = (from until to).mapNotNull { player.getMediaItemAt(it).file }
        player.removeMediaItems(from, to)
        files.forEach(ttsFiles::delete)
    }

    private fun indexOfFirst(predicate: (MediaItem) -> Boolean): Int? =
        (0 until player.mediaItemCount).firstOrNull { predicate(player.getMediaItemAt(it)) }

    private fun lastIndexOf(predicate: (MediaItem) -> Boolean): Int? =
        (player.mediaItemCount - 1 downTo 0).firstOrNull { predicate(player.getMediaItemAt(it)) }

    private companion object {
        const val TAG = "StoryQueue"
        const val STORY_COUNT = 30
        const val PREPARE_AHEAD = 2
        const val ARTICLE_AHEAD = 4
        const val WORDS_PER_MINUTE = 165f
    }
}
