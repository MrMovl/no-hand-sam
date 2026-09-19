package de.reibisch.hnaudio.playback

import android.os.SystemClock
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

data class QueueConfig(val storyCount: Int, val includeAskShow: Boolean, val heard: Set<Long>)

private class StoryText(val extraction: Extraction, val summary: String?)

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
    private val config: suspend () -> QueueConfig,
    private val onStoryStarted: (Story) -> Unit,
) {
    private var stories: List<Story> = emptyList()
    private val extractions = mutableMapOf<Int, Extraction>()

    /** Article + summary per story; cheap to keep, so prepared further ahead than audio. */
    private val texts = mutableMapOf<Int, Deferred<StoryText>>()
    private val audio = mutableMapOf<Int, Deferred<List<File>>>()
    private val appended = mutableSetOf<Int>()
    private val extractPermits = Semaphore(4)
    private val summaryPermits = Semaphore(2)

    /** When the player ran out of items; used to log dead air. */
    private var waitingSince = 0L

    /** Story the listener is on; `stories.size` once the end cue is reached. */
    private val currentStory = MutableStateFlow(0)

    /** Bumped on every playlist transition so waiting coroutines re-check the player. */
    private val positionTick = MutableStateFlow(0)

    private var prefetchJob: Job? = null
    private var articleJob: Job? = null
    private var backJob: Job? = null
    private var articleStory = -1

    var isStarted = false
        private set

    /** True when the story list couldn't be loaded, or the list has been played to the end. */
    val canRestart: Boolean
        get() = player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.kind == ItemKind.END

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.storyIndex?.takeIf { it >= 0 }?.let { currentStory.value = it }
            if (mediaItem?.kind == ItemKind.SUMMARY) stories.getOrNull(mediaItem.storyIndex)?.let(onStoryStarted)
            positionTick.value++
            // The listener only moves forward, so everything before the current item is done.
            scope.launch { removeRange(0, player.currentMediaItemIndex) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player.currentMediaItem?.kind != ItemKind.END) {
                waitingSince = SystemClock.elapsedRealtime()
            }
        }
    }

    init {
        player.addListener(listener)
    }

    fun start() {
        prefetchJob?.cancel()
        backJob?.cancel()
        articleJob?.cancel()
        removeRange(0, player.mediaItemCount)
        cancelPipeline()
        stories = emptyList()
        extractions.clear()
        currentStory.value = 0
        isStarted = true
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        prefetchJob = scope.launch { prefetch() }
        scope.launch { playIntro() }
    }

    /** The story the listener is on, for "save for later". */
    val currentStoryOrNull: Story?
        get() = player.currentMediaItem?.storyIndex?.let { stories.getOrNull(it) }

    /** Immediate feedback that play registered, filling the wait for the first story. */
    private suspend fun playIntro() {
        val files = try {
            speech.render("Loading the Hacker News front page.")
        } catch (e: SpeechException) {
            return
        }
        // Pointless once a story is already queued; it would play after it.
        if (player.mediaItemCount > 0 || !isStarted) {
            files.forEach(ttsFiles::delete)
            return
        }
        append(files.map { queueItem(it, -1, ItemKind.INTRO, null, 0) })
    }

    /** Next button: leave the current story (summary or article) and go to the next one. */
    fun next() {
        if (player.currentMediaItem?.kind == ItemKind.INTRO) {
            removeRange(0, player.currentMediaItemIndex + 1)
            return
        }
        val cur = currentStory.value
        if (!isStarted || cur >= stories.size) return
        articleJob?.cancel()
        backJob?.cancel()
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

    /**
     * Back one story: plays the previous story's summary again, then the current story from
     * its start. Played audio is deleted, so both are re-rendered from the cached summaries.
     */
    fun previousStory() {
        val kind = player.currentMediaItem?.kind ?: return
        val cur = currentStory.value
        if (!isStarted || kind == ItemKind.INTRO || cur < 1 || backJob?.isActive == true) return
        val target = cur - 1
        articleJob?.cancel()
        backJob = scope.launch {
            val targetFiles = renderStory(target)
            if (currentStory.value != cur || targetFiles.isEmpty()) {
                targetFiles.forEach(ttsFiles::delete)
                return@launch // the listener moved on meanwhile
            }
            // Keep the prefetch window from rendering it a second time.
            audio[target] = CompletableDeferred(targetFiles)
            appended += target
            val at = indexOfFirst { it.storyIndex >= cur } ?: player.mediaItemCount
            val targetItems = targetFiles.map { queueItem(it, target, ItemKind.SUMMARY, stories[target], stories.size) }
            player.addMediaItems(at, targetItems)
            player.seekTo(at, 0)
            if (cur >= stories.size) return@launch // we were on the end cue, which stays
            // Replace what's left of the current story (a half-heard summary or an article).
            val from = at + targetItems.size
            removeRange(from, indexOfFirst { it.storyIndex > cur } ?: player.mediaItemCount)
            val curItems = renderStory(cur).map { queueItem(it, cur, ItemKind.SUMMARY, stories[cur], stories.size) }
            insertAfterStory(target, curItems)
        }
    }

    fun release() {
        player.removeListener(listener)
        cancelPipeline()
        prefetchJob?.cancel()
        articleJob?.cancel()
        removeRange(0, player.mediaItemCount)
    }

    private suspend fun prefetch() {
        stories = try {
            val cfg = config()
            hn.topStories(cfg.storyCount, exclude = cfg.heard) { cfg.includeAskShow || !it.isAskOrShow }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading stories failed: $e")
            appendCue("Couldn't load Hacker News. Check your internet connection and press play to try again.")
            return
        }
        Log.i(TAG, "Loaded ${stories.size} stories")
        if (stories.isEmpty()) {
            appendCue("You've heard all the current front page stories. Check back later.")
            return
        }
        val window = scope.launch { currentStory.collect(::updateWindow) }
        try {
            // Stories are prepared in parallel but appended strictly in order.
            for (index in stories.indices) {
                currentStory.first { index <= it + AUDIO_AHEAD }
                if (index < currentStory.value) continue
                val files = try {
                    audioFor(index).await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue // this story was skipped while it was being prepared
                }
                if (index < currentStory.value) {
                    files.forEach(ttsFiles::delete)
                    continue
                }
                appended += index
                append(files.map { queueItem(it, index, ItemKind.SUMMARY, stories[index], stories.size) })
            }
        } finally {
            window.cancel()
        }
        appendCue("That's all the stories for now.")
    }

    /** Starts work for the stories ahead of [cur] and cancels work for stories behind it. */
    private fun updateWindow(cur: Int) {
        for (i in (texts.keys + audio.keys).filter { it < cur }) {
            texts.remove(i)?.cancel()
            audio.remove(i)?.let { job ->
                job.cancel()
                // Rendered but never appended: nobody else will delete these files.
                if (i !in appended) scope.launch { runCatching { job.await() }.getOrNull()?.forEach(ttsFiles::delete) }
            }
        }
        val last = stories.lastIndex
        for (i in cur..minOf(cur + TEXT_AHEAD, last)) textFor(i)
        for (i in cur..minOf(cur + AUDIO_AHEAD, last)) audioFor(i)
    }

    private fun textFor(index: Int): Deferred<StoryText> = texts.getOrPut(index) {
        scope.async {
            val story = stories[index]
            val extraction = extractions[index]
                ?: extractPermits.withPermit { extractor.extract(story.url) }.also { extractions[index] = it }
            val summary = try {
                summaryPermits.withPermit { summaries.summaryFor(story, extraction).text }
            } catch (e: SummaryException) {
                Log.w(TAG, "Summary for #${index + 1} failed: ${e.message}")
                null
            } catch (e: IOException) {
                Log.w(TAG, "Summary for #${index + 1} failed: $e")
                null
            }
            StoryText(extraction, summary)
        }
    }

    private fun audioFor(index: Int): Deferred<List<File>> = audio.getOrPut(index) {
        scope.async {
            textFor(index).await()
            // The speech engine renders one text at a time; make sure the story the listener
            // needs first gets it first, instead of whichever summary arrived first.
            audio[index - 1]?.join()
            renderStory(index)
        }
    }

    /** Intro + summary audio; the summary comes from the disk cache when revisiting. */
    private suspend fun renderStory(index: Int): List<File> {
        val text = textFor(index).await()
        val story = stories[index]
        val intro = "Story ${index + 1}. ${story.title}. ${story.score} points, ${story.commentCount} comments."
        val body = text.summary ?: "Sorry, I couldn't load this one."
        val started = SystemClock.elapsedRealtime()
        return try {
            speech.render("$intro\n\n$body").also {
                Log.i(TAG, "Rendered #${index + 1} in ${SystemClock.elapsedRealtime() - started} ms")
            }
        } catch (e: SpeechException) {
            Log.w(TAG, "Rendering #${index + 1} failed: ${e.message}")
            emptyList()
        }
    }

    private fun cancelPipeline() {
        texts.values.forEach { it.cancel() }
        audio.values.forEach { it.cancel() }
        texts.clear()
        audio.clear()
        appended.clear()
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
        if (waiting) {
            player.seekTo(first, 0)
            if (waitingSince > 0 && player.playWhenReady) {
                Log.i(TAG, "Dead air: ${SystemClock.elapsedRealtime() - waitingSince} ms")
            }
            waitingSince = 0
        }
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
        const val AUDIO_AHEAD = 2
        const val TEXT_AHEAD = 5
        const val ARTICLE_AHEAD = 4
        const val WORDS_PER_MINUTE = 165f
    }
}
