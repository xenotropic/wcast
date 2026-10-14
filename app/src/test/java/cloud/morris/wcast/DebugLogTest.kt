package cloud.morris.wcast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {

    @After
    fun tearDown() {
        DebugLog.enabled = false
        DebugLog.clear()
    }

    @Test
    fun disabledLoggerNeverCaptures() {
        DebugLog.enabled = false
        DebugLog.d("Tag", "invisible")
        assertTrue(DebugLog.snapshot().isEmpty())
        assertEquals(emptyList<String>(), DebugLog.state.value)
    }

    @Test
    fun enabledLoggerCapturesFormattedLine() {
        DebugLog.enabled = true
        DebugLog.d("SpeechController", "hello timing")
        val lines = DebugLog.snapshot()
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue("line must carry the grep-able key-phrase", line.contains(DebugLog.KEY_PHRASE))
        assertTrue("line must carry the tag", line.contains("SpeechController"))
        assertTrue("line must carry the message", line.contains("hello timing"))
    }

    @Test
    fun bufferIsBoundedDroppingOldest() {
        DebugLog.enabled = true
        repeat(DebugLog.MAX_ENTRIES + 50) { i -> DebugLog.d("T", "line $i") }
        val lines = DebugLog.snapshot()
        assertEquals(DebugLog.MAX_ENTRIES, lines.size)
        // The oldest 50 were dropped, so the first retained line is "line 50".
        assertTrue(lines.first().contains("line 50"))
        assertTrue(lines.last().contains("line ${DebugLog.MAX_ENTRIES + 49}"))
    }

    @Test
    fun clearEmptiesBufferAndFlow() {
        DebugLog.enabled = true
        DebugLog.d("T", "a")
        DebugLog.d("T", "b")
        assertFalse(DebugLog.snapshot().isEmpty())
        DebugLog.clear()
        assertTrue(DebugLog.snapshot().isEmpty())
        assertEquals(emptyList<String>(), DebugLog.state.value)
    }
}