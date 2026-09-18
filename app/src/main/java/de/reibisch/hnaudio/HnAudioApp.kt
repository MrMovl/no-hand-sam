package de.reibisch.hnaudio

import android.app.Application
import de.reibisch.hnaudio.data.ArticleExtractor
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.defaultHttpClient

class HnAudioApp : Application() {
    val http by lazy { defaultHttpClient() }
    val hnClient by lazy { HnClient(http) }
    val extractor by lazy { ArticleExtractor(http) }
}
