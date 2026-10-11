package cloud.morris.wcast.data

import android.content.Context
import java.io.File

/**
 * Persists bookmarked articles offline so they can be re-read on a plane (no
 * data) and so a listening position can be remembered across sessions (ticket 11).
 *
 * Storage layout (no new dependencies):
 *  • Each bookmarked article's scrubbed, structured [ArticleSection]s are written
 *    as a JSON file in the app-internal `filesDir/bookmarks/` directory, named by
 *    a stable hash of the title (titles can contain URL-unfriendly characters).
 *  • The saved reading position — the last (sectionId, paragraphIndex) spoken — is
 *    kept in [android.content.SharedPreferences] as a small JSON value keyed by
 *    title, so updating it as playback advances rewrites only a few bytes rather
 *    than the whole article file.
 *  • The set of bookmarked titles is also kept in SharedPreferences as an ordered
 *    list so the bookmarks screen can render in save order without scanning files.
 *
 * Semantics (per the locked-in ticket decisions):
 *  • Unsaving a bookmark deletes both the article file and its position, as if it
 *    had never been stored.
 *  • A reload (re-fetch) overwrites the stored copy but preserves the position.
 */
class BookmarkStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dir = File(appContext.filesDir, DIR_NAME)

    /** A bookmarked article ready for display/TTS, plus its optional saved spot. */
    data class Bookmark(
        val title: String,
        val sections: List<ArticleSection>,
        val sectionId: String?,
        val paragraphIndex: Int?
    )

    /** A lightweight row for the bookmarks list screen. */
    data class Summary(
        val title: String,
        val hasPosition: Boolean
    )

    /** The saved playback position (null sectionId/paragraphIndex = "none saved"). */
    data class Position(val sectionId: String?, val paragraphIndex: Int?)

    private fun fileFor(title: String): File = File(dir, "${title.hashCode()}.json")

    /** True if [title] has a stored offline copy. */
    fun isBookmarked(title: String): Boolean = fileFor(title).exists()

    /**
     * Saves (or overwrites) the stored article for [title]. Does not touch any
     * previously stored position, so a reload preserves the play location.
     */
    fun saveCopy(title: String, sections: List<ArticleSection>) {
        if (!dir.exists()) dir.mkdirs()
        fileFor(title).writeText(BookmarkCodec.encode(title, sections))
        addToIndex(title)
    }

    /** Records the last spoken position for [title]. */
    fun savePosition(title: String, sectionId: String?, paragraphIndex: Int?) {
        if (!isBookmarked(title)) return
        prefs.edit().putString(positionKey(title), encodePosition(sectionId, paragraphIndex)).apply()
    }

    /** Loads a stored article (or null if not bookmarked). */
    fun load(title: String): Bookmark? {
        val file = fileFor(title)
        if (!file.exists()) return null
        val (_, sections) = BookmarkCodec.decode(file.readText())
        val pos = decodePosition(prefs.getString(positionKey(title), null))
        return Bookmark(title, sections, pos.sectionId, pos.paragraphIndex)
    }

    /** Deletes the stored article and its position (as if never stored). */
    fun delete(title: String) {
        fileFor(title).delete()
        prefs.edit().remove(positionKey(title)).apply()
        removeFromIndex(title)
    }

    /** All bookmarked titles in save order. */
    fun summaries(): List<Summary> =
        index().mapNotNull { title ->
            val pos = decodePosition(prefs.getString(positionKey(title), null))
            Summary(title, pos.sectionId != null || pos.paragraphIndex != null)
        }

    private fun index(): MutableList<String> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return mutableListOf()
        return raw.split('\n').filter { it.isNotEmpty() }.toMutableList()
    }

    private fun addToIndex(title: String) {
        val list = index()
        if (title !in list) {
            list.add(title)
            prefs.edit().putString(KEY_INDEX, list.joinToString("\n")).apply()
        }
    }

    private fun removeFromIndex(title: String) {
        val list = index()
        if (list.remove(title)) {
            prefs.edit().putString(KEY_INDEX, list.joinToString("\n")).apply()
        }
    }

    private fun positionKey(title: String): String = "$KEY_POSITION_PREFIX$title"

    private fun encodePosition(sectionId: String?, paragraphIndex: Int?): String {
        if (sectionId == null && paragraphIndex == null) return EMPTY_POSITION
        return "$sectionId${SEP}$paragraphIndex"
    }

    private fun decodePosition(raw: String?): Position {
        if (raw.isNullOrEmpty() || raw == EMPTY_POSITION) return Position(null, null)
        val parts = raw.split(SEP, limit = 2)
        val sectionId = parts[0].ifEmpty { null }
        val paragraphIndex = parts.getOrNull(1)?.toIntOrNull()
        return Position(sectionId, paragraphIndex)
    }

    private companion object {
        const val PREFS_NAME = "wcast_bookmarks"
        const val DIR_NAME = "bookmarks"
        const val KEY_INDEX = "index"
        const val KEY_POSITION_PREFIX = "position_"
        const val EMPTY_POSITION = "-"
        const val SEP = "\u0001"
    }
}
