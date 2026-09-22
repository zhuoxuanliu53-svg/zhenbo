package app.jingting.localplayer.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleMathTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    @Test fun oneShotMustBeFuture() {
        val after = Instant.parse("2026-09-20T15:01:00Z")
        assertNull(ScheduleMath.next(23, 0, 0, "2026-09-20", after, shanghai))
        assertEquals(Instant.parse("2026-09-21T15:00:00Z"), ScheduleMath.next(23, 0, 0, "2026-09-21", after, shanghai))
    }
    @Test fun sundayWrapsToMonday() {
        assertEquals(Instant.parse("2026-09-21T15:00:00Z"), ScheduleMath.next(23, 0, 1, null, Instant.parse("2026-09-20T16:00:00Z"), shanghai))
    }
    @Test fun weeklyDoesNotAddTwentyFourHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        val after = Instant.parse("2026-03-28T22:00:00Z")
        // 02:30 does not exist on spring-forward Sunday: move to 03:30.
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), ScheduleMath.next(2, 30, 64, null, after, berlin))
    }
    @Test fun autumnOverlapUsesFirstOccurrenceOnly() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), ScheduleMath.next(2, 30, 64, null, Instant.parse("2026-10-24T20:00:00Z"), berlin))
        assertEquals(Instant.parse("2026-11-01T01:30:00Z"), ScheduleMath.next(2, 30, 64, null, Instant.parse("2026-10-25T00:45:00Z"), berlin))
    }
    @Test fun handledDatePreventsClockRollbackDuplicate() {
        assertEquals(Instant.parse("2026-09-21T15:00:00Z"), ScheduleMath.next(23, 0, 127, null, Instant.parse("2026-09-20T14:00:00Z"), shanghai, "2026-09-20"))
    }
    @Test fun multipleWeekdaysSelectNearest() {
        assertEquals(Instant.parse("2026-09-23T15:00:00Z"), ScheduleMath.next(23, 0, 5, null, Instant.parse("2026-09-21T16:00:00Z"), shanghai))
    }
    @Test fun exactBoundaryGoesToNextOccurrence() {
        assertEquals(Instant.parse("2026-09-27T15:00:00Z"), ScheduleMath.next(23, 0, 64, null, Instant.parse("2026-09-20T15:00:00Z"), shanghai))
    }
    @Test fun graceWindowIsBounded() { assertFalse(ScheduleMath.isLate(1000, 301000)); assertTrue(ScheduleMath.isLate(1000, 301001)) }
    @Test fun filenameCannotEscapeMusicFolder() { assertEquals(".._.._test_name", ExportNames.normalize("../../test:name.m4a")); assertEquals("枕波音频", ExportNames.normalize("  ")) }
    @Test(expected = IllegalArgumentException::class) fun invalidTimeRejected() { ScheduleMath.next(24, 0, 127, null, Instant.now(), shanghai) }
}
