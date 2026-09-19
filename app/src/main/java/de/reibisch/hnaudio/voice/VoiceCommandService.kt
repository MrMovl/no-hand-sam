package de.reibisch.hnaudio.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.reibisch.hnaudio.HnAudioApp
import de.reibisch.hnaudio.MainActivity
import de.reibisch.hnaudio.playback.ItemKind
import de.reibisch.hnaudio.playback.PlaybackService
import de.reibisch.hnaudio.playback.kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens on the phone's microphone for a few fixed English phrases and turns them into
 * player commands. A separate foreground service of type "microphone", because Android only
 * lets apps use the mic in the background from such a service, started while the app is open.
 */
class VoiceCommandService : Service(), RecognitionListener {
    private val scope = MainScope()
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var controller: MediaController? = null
    private var tone: ToneGenerator? = null
    private var idleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (speech == null && VoiceState.status.value != VoiceStatus.Starting) start()
        return START_NOT_STICKY
    }

    private fun start() {
        VoiceState.status.value = VoiceStatus.Starting
        val app = application as HnAudioApp
        connectController()
        scope.launch {
            try {
                val dir = app.voiceModel.dir
                val (loaded, recognizer) = withContext(Dispatchers.IO) {
                    val m = Model(dir.absolutePath)
                    m to Recognizer(m, SAMPLE_RATE, VoiceCommands.grammar).apply { setWords(true) }
                }
                model = loaded
                speech = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this@VoiceCommandService) }
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                VoiceState.status.value = VoiceStatus.Listening
                Log.i(TAG, "Listening")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start listening: $e")
                VoiceState.status.value = VoiceStatus.Failed(e.message ?: "couldn't start")
                stopSelf()
            }
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleIdleStop(isPlaying)
            })
            scheduleIdleStop(c.isPlaying)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Stop listening after a long pause so the mic doesn't stay on all day. */
    private fun scheduleIdleStop(isPlaying: Boolean) {
        idleJob?.cancel()
        if (isPlaying) return
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.i(TAG, "Nothing played for a while, stopping")
            stopSelf()
        }
    }

    override fun onResult(hypothesis: String) = handle(hypothesis)
    override fun onFinalResult(hypothesis: String) = handle(hypothesis)
    override fun onPartialResult(hypothesis: String) {}
    override fun onTimeout() {}

    override fun onError(exception: Exception) {
        Log.w(TAG, "Recognizer error: $exception")
        VoiceState.status.value = VoiceStatus.Failed(exception.message ?: "recognizer error")
        stopSelf()
    }

    private fun handle(result: String) {
        val heard = VoiceCommands.parse(result) ?: return
        Log.i(TAG, "Heard \"${heard.text}\" (confidence ${"%.2f".format(heard.confidence)}) -> ${heard.command}")
        val command = heard.command ?: return
        val c = controller ?: return
        VoiceState.lastHeard.value = heard.text
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        when (command) {
            VoiceCommand.NEXT -> c.seekToNext()
            VoiceCommand.PREVIOUS_STORY -> c.sendCustomCommand(PlaybackService.PREVIOUS_STORY_COMMAND, Bundle.EMPTY)
            VoiceCommand.READ_ARTICLE -> if (c.currentMediaItem?.kind == ItemKind.SUMMARY) c.seekToPrevious()
            VoiceCommand.REPEAT -> c.seekTo(c.currentMediaItemIndex, 0)
            VoiceCommand.SAVE -> c.sendCustomCommand(PlaybackService.SAVE_COMMAND, Bundle.EMPTY)
            VoiceCommand.PAUSE -> c.pause()
            VoiceCommand.PLAY -> c.play()
        }
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Voice commands")
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceCommandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for voice commands")
            .setContentText("\"next story\", \"read article\", \"save story\", \"pause\"…")
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        speech?.stop()
        speech?.shutdown()
        speech = null
        model?.close()
        model = null
        tone?.release()
        controller?.release()
        scope.cancel()
        if (VoiceState.status.value !is VoiceStatus.Failed) VoiceState.status.value = VoiceStatus.Off
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Voice"
        private const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.reibisch.hnaudio.STOP_LISTENING"
        private const val SAMPLE_RATE = 16000f
        private const val TONE_VOLUME = 60
        private const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCommandService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCommandService::class.java))
        }
    }
}
