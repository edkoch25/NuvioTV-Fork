package com.nuvio.tv.ui.screens.player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapabilityReportTest {

    @Test
    fun format_listsSupportedAndAbsentAndMode() {
        assertEquals(
            "direct: AC3 EAC3 TrueHD · absent: DTS DTS-HD · negotiated: AC3 EAC3 PCM16 · surround: MANUAL",
            AudioCapabilityReport.format(
                supported = listOf("AC3", "EAC3", "TrueHD"),
                absent = listOf("DTS", "DTS-HD"),
                negotiated = "AC3 EAC3 PCM16",
                surroundMode = "MANUAL"
            )
        )
    }

    @Test
    fun format_saysNoneRatherThanEmptyList() {
        assertEquals(
            "direct: none · absent: AC3 · negotiated: unknown · surround: NEVER",
            AudioCapabilityReport.format(emptyList(), listOf("AC3"), "unknown", "NEVER")
        )
        assertEquals(
            "direct: AC3 · absent: none · negotiated: AC3 · surround: AUTO",
            AudioCapabilityReport.format(listOf("AC3"), emptyList(), "AC3", "AUTO")
        )
    }

    @Test
    fun format_isSingleLineAndCompactEnoughForATvRow() {
        val line = AudioCapabilityReport.format(
            listOf("AC3", "EAC3", "EAC3-JOC", "TrueHD", "DTS", "DTS-HD"),
            emptyList(),
            "AC3 EAC3 PCM16",
            "MANUAL"
        )
        assertTrue("must stay one line", !line.contains("\n"))
        assertTrue("unexpectedly long: ${line.length}", line.length < 160)
    }

    @Test
    fun latest_isNullUntilCaptured() {
        AudioCapabilityReport.reset()
        assertNull(AudioCapabilityReport.latest)
    }
}
