package app.jingting.localplayer

import app.jingting.localplayer.subtitle.*
import org.junit.Assert.*
import org.junit.Test

class SubtitleTest {
    @Test fun overlappingCuesAndBlankIntervalsRemainDistinct() {
        val cues=listOf(SubtitleCue(1000000,3000000,"甲\n乙"),SubtitleCue(2000000,2500000,"Second"),SubtitleCue(4000000,5000000,"甲\n乙"))
        assertEquals(cues,SubRip.decode(SubRip.encode(cues)))
        assertEquals("甲\n乙\nSecond",SubRip.current(cues,2200000))
        assertEquals("",SubRip.current(cues,3000000))
        assertEquals("甲\n乙",SubRip.current(cues,4000000))
    }
    @Test fun concatenationUsesActualOffsetsAndClipsSourceStart() {
        val timeline=ExportTimeline(listOf(ExportSegment("a",500000,2000000,0),ExportSegment("silent",0,700000,2000000),ExportSegment("b",0,3000000,2700000)))
        val cues=timeline.map(mapOf("a" to listOf(SubtitleCue(0,1000000,"a")),"b" to listOf(SubtitleCue(1000000,2000000,"b"))))
        assertEquals(listOf(SubtitleCue(0,500000,"a"),SubtitleCue(3700000,4700000,"b")),cues)
    }
    @Test fun ocrRepeatedTextAfterBlankIsNotMergedAndSingleFrameNoiseIsDropped() {
        val s=SubtitleStabilizer()
        s.accept(0,"hello");s.accept(250000,"hello");s.accept(500000,"");s.accept(750000,"noise");s.accept(1000000,"");s.accept(1250000,"hello");s.accept(1500000,"hello")
        assertEquals(listOf(SubtitleCue(0,500000,"hello"),SubtitleCue(1250000,1750000,"hello")),s.finish(2000000))
    }
}
