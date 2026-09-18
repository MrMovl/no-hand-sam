package de.reibisch.hnaudio.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.reibisch.hnaudio.playback.ItemKind
import de.reibisch.hnaudio.playback.PlaybackService
import de.reibisch.hnaudio.playback.articleUrl
import de.reibisch.hnaudio.playback.kind
import de.reibisch.hnaudio.playback.storyId
import kotlinx.coroutines.delay

private data class NowPlaying(
    val title: String? = null,
    val label: String? = null,
    val site: String? = null,
    val kind: ItemKind? = null,
    val storyId: Long? = null,
    val url: String? = null,
    val isPlaying: Boolean = false,
    val preparing: Boolean = false,
)

private fun Player.snapshot(): NowPlaying {
    val item = currentMediaItem
    return NowPlaying(
        title = item?.mediaMetadata?.title?.toString(),
        label = item?.mediaMetadata?.albumTitle?.toString(),
        site = item?.mediaMetadata?.artist?.toString(),
        kind = item?.kind,
        storyId = item?.storyId,
        url = item?.articleUrl,
        isPlaying = isPlaying,
        preparing = playWhenReady && (item == null || playbackState == Player.STATE_ENDED ||
            playbackState == Player.STATE_BUFFERING),
    )
}

@Composable
private fun rememberMediaController(context: Context): MediaController? {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onOpenSettings: () -> Unit, onOpenSaved: () -> Unit, onOpenDebug: () -> Unit) {
    val context = LocalContext.current
    val controller = rememberMediaController(context)
    var state by remember { mutableStateOf(NowPlaying()) }
    var progress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        state = c.snapshot()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = player.snapshot()
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }
    LaunchedEffect(controller, state.isPlaying, state.title) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            val duration = c.duration
            progress = if (duration == C.TIME_UNSET || duration <= 0) 0f else (c.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            if (!state.isPlaying) break
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HN Hands-Free") },
                actions = {
                    TextButton(onClick = onOpenSaved) { Text("Saved") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                            DropdownMenuItem(text = { Text("Debug") }, onClick = { menu = false; onOpenDebug() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onStory = state.storyId != null
            when {
                state.title != null -> {
                    state.label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    Text(state.title!!, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    state.site?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                state.preparing -> Text("Preparing the first story…", style = MaterialTheme.typography.titleMedium)
                else -> Text(
                    "Press play here or on your headphones.\nNext skips a story, Previous reads the full article.",
                    textAlign = TextAlign.Center,
                )
            }
            val c = controller
            Button(
                onClick = { if (c?.isPlaying == true) c.pause() else c?.play() },
                enabled = c != null,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) { Text(if (state.isPlaying) "Pause" else "Play", style = MaterialTheme.typography.headlineSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val inArticle = state.kind == ItemKind.ARTICLE || state.kind == ItemKind.ARTICLE_CUE
                FilledTonalButton(
                    onClick = { c?.seekToPrevious() },
                    enabled = c != null && onStory,
                    modifier = Modifier.weight(1f).height(72.dp),
                ) { Text(if (inArticle) "Restart paragraph" else "Read article", textAlign = TextAlign.Center) }
                FilledTonalButton(
                    onClick = { c?.seekToNext() },
                    enabled = c != null && state.title != null,
                    modifier = Modifier.weight(1f).height(72.dp),
                ) { Text("Next story") }
            }
            if (onStory) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            c?.sendCustomCommand(PlaybackService.SAVE_COMMAND, Bundle.EMPTY)
                            Toast.makeText(context, "Saved for later", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    state.url?.let { url ->
                        OutlinedButton(onClick = { openInBrowser(context, url) }, modifier = Modifier.weight(1f)) {
                            Text("Article")
                        }
                    }
                    OutlinedButton(
                        onClick = { openInBrowser(context, "https://news.ycombinator.com/item?id=${state.storyId}") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Comments") }
                }
            }
        }
    }
}
