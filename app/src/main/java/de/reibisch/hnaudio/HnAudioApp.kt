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
import kotlinx.coroutines.flow.first
import java.io.File

class HnAudioApp : Application() {
    val http by lazy { defaultHttpClient() }
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
