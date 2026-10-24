package cloud.morris.wcast.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a bookmarked article's structured [ArticleSection]s (and title) to a
 * JSON string and back, so an article can be stored offline (ticket 11) and
 * reconstructed for display and TTS without re-fetching from Wikipedia.
 *
 * Kept as pure functions (no Android dependencies) so the round-trip can be unit
 * tested in the same way the normalization logic is. The actual filesystem and
 * preference persistence lives in [BookmarkStore]; this object only handles the
 * text <-> structure mapping.
 */
object BookmarkCodec {

    private const val KEY_TITLE = "title"
    private const val KEY_SECTIONS = "sections"
    private const val KEY_IMAGE = "image"
    private const val KEY_IMAGE_URL = "url"
    private const val KEY_IMAGE_CAPTION = "caption"
    private const val KEY_ID = "id"
    private const val KEY_HEADING = "heading"
    private const val KEY_LEVEL = "level"
    private const val KEY_PARAGRAPHS = "paragraphs"
    private const val KEY_TEXT = "text"
    private const val KEY_LINKS = "links"
    private const val KEY_LINK_TITLE = "title"
    private const val KEY_LINK_START = "start"
    private const val KEY_LINK_END = "end"

    /** Encodes a bookmarked article (title + sections + image) as a JSON document. */
    fun encode(title: String, sections: List<ArticleSection>, image: ArticleImage? = null): String {
        val root = JSONObject()
        root.put(KEY_TITLE, title)
        root.put(KEY_SECTIONS, JSONArray().apply {
            sections.forEach { put(encodeSection(it)) }
        })
        if (image != null) {
            root.put(KEY_IMAGE, JSONObject().apply {
                put(KEY_IMAGE_URL, image.url)
                if (image.caption != null) put(KEY_IMAGE_CAPTION, image.caption)
            })
        }
        return root.toString()
    }

    /** Decodes a JSON document back into (title, sections, image). */
    fun decode(json: String): Triple<String, List<ArticleSection>, ArticleImage?> {
        val root = JSONObject(json)
        val title = root.getString(KEY_TITLE)
        val sections = root.getJSONArray(KEY_SECTIONS).let { arr ->
            buildList {
                for (i in 0 until arr.length()) add(decodeSection(arr.getJSONObject(i)))
            }
        }
        val image = if (root.isNull(KEY_IMAGE)) null else {
            root.optJSONObject(KEY_IMAGE)?.let { obj ->
                val url = obj.optString(KEY_IMAGE_URL).takeIf { it.isNotEmpty() } ?: return@let null
                ArticleImage(
                    url = url,
                    caption = obj.optString(KEY_IMAGE_CAPTION).takeIf { it.isNotEmpty() }
                )
            }
        }
        return Triple(title, sections, image)
    }

    private fun encodeSection(section: ArticleSection): JSONObject = JSONObject().apply {
        put(KEY_ID, section.id)
        if (section.heading != null) put(KEY_HEADING, section.heading) else put(KEY_HEADING, JSONObject.NULL)
        put(KEY_LEVEL, section.level)
        put(KEY_PARAGRAPHS, JSONArray().apply {
            section.paragraphs.forEach { put(encodeParagraph(it)) }
        })
    }

    private fun decodeSection(obj: JSONObject): ArticleSection = ArticleSection(
        id = obj.getString(KEY_ID),
        heading = if (obj.isNull(KEY_HEADING)) null else obj.getString(KEY_HEADING),
        level = obj.getInt(KEY_LEVEL),
        paragraphs = obj.getJSONArray(KEY_PARAGRAPHS).let { arr ->
            buildList {
                for (i in 0 until arr.length()) add(decodeParagraph(arr.getJSONObject(i)))
            }
        }
    )

    private fun encodeParagraph(paragraph: ArticleParagraph): JSONObject = JSONObject().apply {
        put(KEY_TEXT, paragraph.text)
        put(KEY_LINKS, JSONArray().apply {
            paragraph.links.forEach { put(encodeLink(it)) }
        })
    }

    private fun decodeParagraph(obj: JSONObject): ArticleParagraph = ArticleParagraph(
        text = obj.getString(KEY_TEXT),
        links = obj.getJSONArray(KEY_LINKS).let { arr ->
            buildList {
                for (i in 0 until arr.length()) add(decodeLink(arr.getJSONObject(i)))
            }
        }
    )

    private fun encodeLink(link: ArticleLink): JSONObject = JSONObject().apply {
        put(KEY_LINK_TITLE, link.title)
        put(KEY_LINK_START, link.start)
        put(KEY_LINK_END, link.end)
    }

    private fun decodeLink(obj: JSONObject): ArticleLink = ArticleLink(
        title = obj.getString(KEY_LINK_TITLE),
        start = obj.getInt(KEY_LINK_START),
        end = obj.getInt(KEY_LINK_END)
    )
}
