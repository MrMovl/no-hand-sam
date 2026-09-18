package de.reibisch.hnaudio.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.reibisch.hnaudio.HnAudioApp
import de.reibisch.hnaudio.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private var queue: StoryQueue? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as HnAudioApp
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
            speechRate = { app.settings.speechRate.first() },
        ).also { queue = it }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, HnPlayer(exo, queue))
            .setSessionActivity(openApp)
            .build()
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
}
