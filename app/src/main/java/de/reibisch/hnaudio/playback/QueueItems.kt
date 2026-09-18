package de.reibisch.hnaudio.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.reibisch.hnaudio.data.Story
import java.io.File

/** What a playlist entry is, which decides how Next and Previous behave on it. */
enum class ItemKind { SUMMARY, ARTICLE_CUE, ARTICLE, END }

private const val EXTRA_STORY = "story_index"
private const val EXTRA_KIND = "kind"

fun queueItem(file: File, storyIndex: Int, kind: ItemKind, story: Story?, storyCount: Int): MediaItem {
    val label = when (kind) {
        ItemKind.SUMMARY -> "Story ${storyIndex + 1} of $storyCount"
        ItemKind.ARTICLE_CUE, ItemKind.ARTICLE -> "Full article · story ${storyIndex + 1}"
        ItemKind.END -> "Hacker News"
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(story?.title ?: "Hacker News")
        .setArtist(story?.domain ?: story?.let { "news.ycombinator.com" } ?: "HN Hands-Free")
        .setAlbumTitle(label)
        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        .setExtras(Bundle().apply {
            putInt(EXTRA_STORY, storyIndex)
            putString(EXTRA_KIND, kind.name)
        })
        .build()
    return MediaItem.Builder()
        .setMediaId("$kind/$storyIndex/${file.name}")
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(metadata)
        .build()
}

val MediaItem.storyIndex: Int get() = mediaMetadata.extras?.getInt(EXTRA_STORY, -1) ?: -1

val MediaItem.kind: ItemKind?
    get() = mediaMetadata.extras?.getString(EXTRA_KIND)?.let { runCatching { ItemKind.valueOf(it) }.getOrNull() }

/** Only set on the service-side player; controllers don't see local URIs. */
val MediaItem.file: File? get() = localConfiguration?.uri?.path?.let(::File)
