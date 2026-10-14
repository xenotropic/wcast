package cloud.morris.wcast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny, in-memory debug logger keyed to the "Debug logs" setting in
 * Settings → Debug (see [SettingsStore.debugLogsEnabled]).
 *
 * The point of this over `android.util.Log` is that a user debugging on-device
 * without ADB can flip the setting on, reproduce the problem, then open the log
 * viewer and copy the captured lines into a ticket/file.
 *
 * ## Grep-ability (see AGENTS.md "Debug logging")
 *
 * Every app-level debug statement must go through [d]; they are all findable by
 * grepping the source for `DebugLog.d` (or the constant [KEY_PHRASE] /
 * the literal `WCAST_DEBUG`), which is what makes them cheap to strip later.
 * Each buffered line is prefixed with [KEY_PHRASE] so the runtime output is
 * equally easy to search.
 *
 * This object is deliberately Android-free (no `android.util.Log`, no Context)
 * so it can be exercised by plain JVM unit tests. State is a bounded ring
 * buffer kept in memory only — no disk I/O, no new permissions.
 */
object DebugLog {

    /** Grep-able key-phrase shared by the in-code constant and the runtime lines. */
    const val KEY_PHRASE = "WCAST_DEBUG"

    /** Upper bound on retained lines; oldest entries are dropped beyond this. */
    const val MAX_ENTRIES = 1000

    private val buffer = ArrayDeque<String>()

    private val _state = MutableStateFlow<List<String>>(emptyList())

    /** Live view of the buffered lines for the log viewer to collect. */
    val state: StateFlow<List<String>> = _state.asStateFlow()

    /**
     * Master switch. When false, [d] is a no-op (no allocation of a formatted
     * line). Mirrored from [SettingsStore.debugLogsEnabled] by the activities.
     */
    @Volatile
    var enabled: Boolean = false

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Appends a single debug line if [enabled]. Thread-safe: TTS/MediaPlayer
     * callbacks arrive on several threads, so the buffer and flow are guarded.
     */
    @Synchronized
    fun d(tag: String, message: String) {
        if (!enabled) return
        buffer.addLast("[$KEY_PHRASE] ${timestamp.format(Date())} $tag: $message")
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _state.value = buffer.toList()
    }

    /** Drops every buffered line (used by the viewer's clear action). */
    @Synchronized
    fun clear() {
        buffer.clear()
        _state.value = emptyList()
    }

    /** Returns an immutable copy of the current buffer. */
    @Synchronized
    fun snapshot(): List<String> = buffer.toList()
}