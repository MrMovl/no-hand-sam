package de.reibisch.hnaudio.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.reibisch.hnaudio.HnAudioApp
import de.reibisch.hnaudio.MainActivity
import de.reibisch.hnaudio.settings.SavedStory
import de.reibisch.hnaudio.settings.Settings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private var queue: StoryQueue? = null
    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        val app = application as HnAudioApp
        settings = app.settings
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps Wi-Fi awake while playing, since the next stories are fetched in the background.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val queue = StoryQueue(
            player = exo,
            scope = scope,
            hn = app.hnClient,
            extractor = app.extractor,
            summaries = app.summaries,
            speech = app.speech,
            ttsFiles = app.ttsFiles,
            speechRate = { settings.speechRate.first() },
            config = {
                QueueConfig(
                    storyCount = settings.storyCount.first(),
                    includeAskShow = settings.includeAskShow.first(),
                    heard = settings.heardIds(),
                )
            },
            onStoryStarted = { story -> scope.launch { settings.markHeard(story.id) } },
        ).also { queue = it }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Long-pressing play/pause usually belongs to the voice assistant, so "save for later"
        // lives in the notification (and the app) instead of on the headset.
        val saveButton = CommandButton.Builder(CommandButton.ICON_BOOKMARK_UNFILLED)
            .setDisplayName("Save for later")
            .setSessionCommand(SAVE_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val backButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Previous story")
            .setSessionCommand(PREVIOUS_STORY_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session = MediaSession.Builder(this, HnPlayer(exo, queue))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(ImmutableList.of(backButton, saveButton))
            .setCallback(SessionCallback())
            .build()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SAVE_COMMAND)
                        .add(PREVIOUS_STORY_COMMAND)
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SAVE_COMMAND.customAction -> Unit
                PREVIOUS_STORY_COMMAND.customAction -> {
                    queue?.previousStory()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val story = queue?.currentStoryOrNull
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            scope.launch {
                settings.save(SavedStory(story.id, story.title, story.url))
                Toast.makeText(this@PlaybackService, "Saved for later", Toast.LENGTH_SHORT).show()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        queue?.release()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        val SAVE_COMMAND = SessionCommand("de.reibisch.hnaudio.SAVE", Bundle.EMPTY)
        val PREVIOUS_STORY_COMMAND = SessionCommand("de.reibisch.hnaudio.PREVIOUS_STORY", Bundle.EMPTY)
    }
}
