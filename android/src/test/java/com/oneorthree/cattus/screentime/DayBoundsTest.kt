package com.oneorthree.cattus.screentime

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DayBoundsTest {
  private fun millis(z: ZonedDateTime) = z.toInstant().toEpochMilli()

  @Test fun todayEndsAtNowAndPastDayAtNextMidnight() {
    val tz = TimeZone.getTimeZone("Asia/Seoul")
    val now = millis(ZonedDateTime.of(2026, 11, 8, 15, 30, 0, 0, ZoneId.of("Asia/Seoul")))
    val today = DayBounds.of(now, 0, tz)
    assertEquals("2026-11-08", today.date)
    assertEquals(millis(ZonedDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneId.of("Asia/Seoul"))), today.begin)
    assertEquals(now, today.end)
    val yesterday = DayBounds.of(now, -1, tz)
    assertEquals("2026-11-07", yesterday.date)
    assertEquals(today.begin, yesterday.end)
  }

  @Test fun dstDayIsNot24Hours() {
    val zone = ZoneId.of("America/New_York")
    val tz = TimeZone.getTimeZone(zone)
    val now = millis(ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, zone))
    val springForward = DayBounds.of(now, -1, tz) // 2026-03-08, 23 hours long
    assertEquals("2026-03-08", springForward.date)
    assertEquals(23L * 3_600_000L, springForward.end - springForward.begin)
    val fallBackNow = millis(ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, zone))
    val fallBack = DayBounds.of(fallBackNow, -1, tz) // 2026-11-01, 25 hours long
    assertEquals(25L * 3_600_000L, fallBack.end - fallBack.begin)
  }

  @Test fun dateKeyUsesTheGivenZone() {
    val instant = millis(ZonedDateTime.of(2026, 11, 8, 20, 0, 0, 0, ZoneId.of("UTC")))
    assertEquals("2026-11-08", DayBounds.dateKey(instant, TimeZone.getTimeZone("UTC")))
    assertEquals("2026-11-09", DayBounds.dateKey(instant, TimeZone.getTimeZone("Asia/Seoul")))
  }
}
