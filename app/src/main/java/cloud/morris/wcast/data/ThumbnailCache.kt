package cloud.morris.wcast.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches an article's low-resolution main image (ticket 33) so the
 * article screen and the media notification can show local artwork without a
 * dedicated image-loading library. Images are a few tens of KB, kept in
 * `filesDir/thumbnails/` and keyed by the article title's hash.
 *
 * `ensure` is a no-op when the file is already cached, so reopening a bookmarked
 * article offline still shows its thumbnail (the file was written at fetch time).
 */
class ThumbnailCache(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    /** The cached image file for [title], or null when it has not been fetched. */
    fun cached(title: String): File? = fileFor(title).takeIf { it.exists() }

    /**
     * Returns the cached file for [url]/[title], downloading it first if
     * necessary. Returns null when [url] is blank or the download fails, in
     * which case callers fall back to whatever (if anything) they already had.
     */
    suspend fun ensure(title: String, url: String?): File? {
        if (url.isNullOrBlank()) return null
        val file = fileFor(title)
        if (file.exists()) return file
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val bytes = connection.inputStream.use { it.readBytes() }
                if (connection.responseCode !in 200..299 || bytes.isEmpty()) {
                    null
                } else {
                    dir.mkdirs()
                    val tmp = File(dir, "${file.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (tmp.renameTo(file)) file else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fileFor(title: String): File = File(dir, "${title.hashCode()}.img")

    private companion object {
        const val DIR_NAME = "thumbnails"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "Wcast/1.0 (Android; Wikipedia reader)"
    }
}