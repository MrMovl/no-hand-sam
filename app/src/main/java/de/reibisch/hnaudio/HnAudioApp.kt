package de.reibisch.hnaudio

import android.app.Application
import de.reibisch.hnaudio.data.ArticleExtractor
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.defaultHttpClient
import de.reibisch.hnaudio.settings.Settings
import de.reibisch.hnaudio.summary.GeminiSummarizer
import de.reibisch.hnaudio.summary.StorySummaries
import de.reibisch.hnaudio.summary.SummaryCache
import de.reibisch.hnaudio.summary.UsageLog
import de.reibisch.hnaudio.tts.SystemSpeechRenderer
import de.reibisch.hnaudio.tts.TtsFiles
import de.reibisch.hnaudio.voice.VoiceModel
import kotlinx.coroutines.flow.first
import java.io.File

class HnAudioApp : Application() {
    val ttsFiles by lazy { TtsFiles(cacheDir) }
    val speech by lazy { SystemSpeechRenderer(this, ttsFiles, speechRate = { settings.speechRate.first() }) }

    override fun onCreate() {
        super.onCreate()
        Thread { ttsFiles.trim() }.start()
    }

    val http by lazy { defaultHttpClient() }
    val voiceModel by lazy { VoiceModel(filesDir, cacheDir, http) }
    val settings by lazy { Settings(this) }
    val hnClient by lazy { HnClient(http) }
    val extractor by lazy { ArticleExtractor(http) }
    val summaries by lazy {
        StorySummaries(
            hn = hnClient,
            summarizer = GeminiSummarizer(
                http = http,
                apiKey = settings::apiKey,
                model = { settings.model.first() },
                usage = UsageLog(),
            ),
            cache = SummaryCache(File(filesDir, "summaries")),
        )
    }
}
