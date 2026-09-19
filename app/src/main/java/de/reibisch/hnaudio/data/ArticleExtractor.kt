package de.reibisch.hnaudio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class ArticleExtractor(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun extract(url: String?): Extraction {
        if (url.isNullOrBlank()) return Extraction.Failed(FailureReason.NoUrl)
        ArticleParser.classifyUrl(url)?.let { return Extraction.Failed(it) }
        ArticleParser.githubRepo(url)?.let { (owner, repo) ->
            // Fall through to the normal page if the README can't be fetched.
            fetchReadme(owner, repo)?.let { return it }
        }
        return try {
            fetchArticle(url)
        } catch (e: InterruptedIOException) {
            Extraction.Failed(FailureReason.Timeout)
        } catch (e: IOException) {
            Extraction.Failed(FailureReason.Network, e.message)
        }
    }

    private suspend fun fetchArticle(url: String): Extraction {
        val request = Request.Builder().url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return http.fetch(request) { response -> readArticle(response) }
    }

    private suspend fun readArticle(response: Response): Extraction {
        if (!response.isSuccessful) {
            val reason = if (response.code in setOf(401, 402, 403, 451)) {
                FailureReason.Blocked
            } else {
                FailureReason.HttpError
            }
            return Extraction.Failed(reason, "HTTP ${response.code}")
        }
        val type = response.body.contentType()
        if (type?.subtype == "pdf") return Extraction.Failed(FailureReason.Pdf)
        if (type != null && type.subtype !in setOf("html", "xhtml+xml")) {
            return Extraction.Failed(FailureReason.NotHtml, "$type")
        }
        if (response.body.contentLength() > MAX_BYTES) {
            return Extraction.Failed(FailureReason.NotHtml, "too large")
        }
        val html = response.body.string()
        val finalUrl = response.request.url.toString()
        return withContext(Dispatchers.Default) { ArticleParser.parse(finalUrl, html) }
    }

    private suspend fun fetchReadme(owner: String, repo: String): Extraction? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/readme")
            .header("Accept", "application/vnd.github.html+json")
            .build()
        val result = try {
            http.fetch(request) { response ->
                if (response.isSuccessful) ArticleParser.parseReadme("$owner/$repo", response.body.string()) else null
            }
        } catch (e: IOException) {
            null
        }
        return result.takeIf { it is Extraction.Article }
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
