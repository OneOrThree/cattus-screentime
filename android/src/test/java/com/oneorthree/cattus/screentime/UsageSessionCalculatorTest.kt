package com.oneorthree.cattus.screentime

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN
import android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSessionCalculatorTest {
  private val min = 60_000L
  private val hour = 60 * min
  private val day = 24 * hour

  // The range under test is the day [0, day). Times below are offsets from its start.
  private val begin = 10 * day
  private val end = 11 * day

  private class FakeSource(
    private val events: List<RawEvent>,
    private val stats: List<DailyStat> = emptyList(),
  ) : UsageSource {
    override fun events(begin: Long, end: Long) =
      events.filter { it.timeStamp in begin until end }.sortedBy { it.timeStamp }

    override fun dailyStats(begin: Long, end: Long) = stats
  }

  private fun ev(type: Int, at: Long, pkg: String? = "a", cls: String? = "Main") =
    RawEvent(type, at, pkg, cls)

  private fun resumed(at: Long, pkg: String = "a", cls: String = "Main") = ev(ACTIVITY_RESUMED, at, pkg, cls)
  private fun paused(at: Long, pkg: String = "a", cls: String = "Main") = ev(ACTIVITY_PAUSED, at, pkg, cls)

  private fun confirmed(source: UsageSource, selection: Set<String>? = null, b: Long = begin, e: Long = end) =
    UsageSessionCalculator.confirmedBreakdown(source, selection, b, e)

  @Test fun simpleSessionIsCounted() {
    val result = confirmed(FakeSource(listOf(resumed(begin + hour), paused(begin + 2 * hour))))
    assertNotNull(result)
    assertEquals(hour, result!!.totalMillis)
    assertEquals(mapOf("a" to hour), result.byPackage)
  }

  @Test fun pausedThenStoppedCountsOnce() {
    val result = confirmed(
      FakeSource(listOf(resumed(begin + hour), paused(begin + 2 * hour), ev(ACTIVITY_STOPPED, begin + 2 * hour))),
    )
    assertEquals(hour, result!!.totalMillis)
  }

  @Test fun sessionAcrossMidnightIsClippedToEachDay() {
    // Resumed 22:00 on day 9, paused 02:00 on day 10: 2 hours belong to each day.
    val source = FakeSource(listOf(resumed(begin - 2 * hour), paused(begin + 2 * hour)))
    val previous = confirmed(source, b = begin - day, e = begin)
    // The previous day sees no event inside its own range except RESUMED, so it is confirmed
    // only because the RESUMED lies in it; the open session is closed at the day's end.
    assertEquals(2 * hour, previous!!.totalMillis)
    val today = UsageSessionCalculator.foregroundMillis(source, null, begin, end)
    assertEquals(2 * hour, today)
  }

  @Test fun sessionStartedBeforeRangeAndStillOpenIsClosedAtRangeEnd() {
    val source = FakeSource(listOf(resumed(begin - hour), paused(begin + hour), resumed(end - hour)))
    val result = confirmed(source)
    assertEquals(2 * hour, result!!.totalMillis)
  }

  @Test fun screenOffClosesOpenSessions() {
    val source = FakeSource(
      listOf(resumed(begin + hour), ev(SCREEN_NON_INTERACTIVE, begin + 2 * hour, null, null), paused(begin + 9 * hour)),
    )
    val result = confirmed(source)
    // The later PAUSED has no open session left, but its key was opened in this scan, so it is
    // a normal close and not an unmatched terminal event.
    assertEquals(hour, result!!.totalMillis)
  }

  @Test fun deviceShutdownClosesOpenSessions() {
    val source = FakeSource(listOf(resumed(begin + hour), ev(DEVICE_SHUTDOWN, begin + 90 * min, null, null)))
    assertEquals(30 * min, confirmed(source)!!.totalMillis)
  }

  @Test fun splitScreenCountsEachAppItself() {
    val source = FakeSource(
      listOf(
        resumed(begin + hour, "a"),
        resumed(begin + hour, "b"),
        paused(begin + 2 * hour, "a"),
        paused(begin + 2 * hour, "b"),
      ),
    )
    val result = confirmed(source)!!
    assertEquals(mapOf("a" to hour, "b" to hour), result.byPackage)
    assertEquals(2 * hour, result.totalMillis)
  }

  @Test fun overlappingActivitiesOfOnePackageCountOnce() {
    val source = FakeSource(
      listOf(
        resumed(begin + 0, "a", "One"),
        resumed(begin + 10 * min, "a", "Two"),
        paused(begin + 20 * min, "a", "One"),
        paused(begin + 30 * min, "a", "Two"),
      ),
    )
    assertEquals(30 * min, confirmed(source)!!.totalMillis)
  }

  @Test fun otherAppsStoppedEventDoesNotCloseThisAppsSession() {
    // A paused, B resumed, A stopped: B must stay open.
    val source = FakeSource(
      listOf(
        resumed(begin + 0, "a"),
        paused(begin + 10 * min, "a"),
        resumed(begin + 10 * min, "b"),
        ev(ACTIVITY_STOPPED, begin + 11 * min, "a", "Main"),
        paused(begin + 40 * min, "b"),
      ),
    )
    val result = confirmed(source)!!
    assertEquals(10 * min, result.byPackage["a"])
    assertEquals(30 * min, result.byPackage["b"])
  }

  @Test fun unmatchedTerminalEventMakesConfirmedNull() {
    // The PAUSED belongs to a session that began more than 24 hours before the range.
    val source = FakeSource(listOf(paused(begin + hour)))
    assertNull(confirmed(source))
  }

  @Test fun unmatchedTerminalEventOfAnotherAppDoesNotCountWhenFiltered() {
    val source = FakeSource(listOf(paused(begin + hour, "other"), resumed(begin + 2 * hour), paused(begin + 3 * hour)))
    assertEquals(hour, confirmed(source, setOf("a"))!!.totalMillis)
  }

  @Test fun noLifecycleEventsMeansConfirmedNull() {
    val source = FakeSource(listOf(ev(SCREEN_NON_INTERACTIVE, begin + hour, null, null)))
    assertNull(confirmed(source))
  }

  @Test fun missingPauseMakesConfirmedNull() {
    val source = FakeSource(listOf(resumed(begin + hour), resumed(begin + 2 * hour), paused(begin + 3 * hour)))
    assertNull(confirmed(source))
  }

  @Test fun selectionFiltersPackages() {
    val source = FakeSource(
      listOf(resumed(begin + hour, "a"), paused(begin + 2 * hour, "a"), resumed(begin + 3 * hour, "b"), paused(begin + 5 * hour, "b")),
    )
    assertEquals(mapOf("b" to 2 * hour), confirmed(source, setOf("b"))!!.byPackage)
    assertEquals(3 * hour, confirmed(source, null)!!.totalMillis)
    // An empty set means all apps, not "no app".
    assertEquals(3 * hour, confirmed(source, emptySet())!!.totalMillis)
  }

  @Test fun emptyRangeIsNull() {
    assertNull(confirmed(FakeSource(emptyList()), b = begin, e = begin))
  }

  @Test fun fallbackUsesDailyStatsWhenLogDoesNotCoverTheDay() {
    val stats = listOf(
      DailyStat("a", begin, end, 20 * min),
      DailyStat("b", begin, end, 10 * min),
      DailyStat("neighbour", begin - day, begin, 99 * min), // does not overlap the range
    )
    val source = FakeSource(emptyList(), stats)
    assertEquals(30 * min, UsageSessionCalculator.foregroundMillis(source, null, begin, end))
    val breakdown = UsageSessionCalculator.foregroundBreakdown(source, null, begin, end)
    assertEquals(30 * min, breakdown.totalMillis)
    assertEquals(mapOf("a" to 20 * min, "b" to 10 * min), breakdown.byPackage)
    assertEquals(10 * min, UsageSessionCalculator.foregroundMillis(source, setOf("b"), begin, end))
  }

  @Test fun unmatchedTerminalTakesTheLargerOfScanAndFallbackAsOneMap() {
    // Scan: a = 20 min (+ an unmatched PAUSED). Fallback: a = 15, b = 10 -> 25 min.
    val source = FakeSource(
      listOf(paused(begin + min, "x"), resumed(begin + hour, "a"), paused(begin + hour + 20 * min, "a")),
      listOf(DailyStat("a", begin, end, 15 * min), DailyStat("b", begin, end, 10 * min)),
    )
    val breakdown = UsageSessionCalculator.foregroundBreakdown(source, null, begin, end)
    assertEquals(25 * min, breakdown.totalMillis)
    assertEquals(breakdown.totalMillis, breakdown.byPackage.values.sum())
    assertEquals(25 * min, UsageSessionCalculator.foregroundMillis(source, null, begin, end))
  }

  @Test fun reconstructedBreakdownTotalEqualsSumOfApps() {
    val source = FakeSource(
      listOf(
        resumed(begin + hour, "a"), paused(begin + hour + 7 * min + 999, "a"),
        resumed(begin + 3 * hour, "b"), paused(begin + 3 * hour + 5 * min + 1, "b"),
      ),
    )
    val b = UsageSessionCalculator.foregroundBreakdown(source, null, begin, end)
    assertEquals(b.totalMillis, b.byPackage.values.sum())
    assertTrue(b.byPackage.values.all { it > 0 })
  }
}
