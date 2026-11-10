package com.oneorthree.cattus.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHistoryTest {
  private fun record(entries: List<SelectionHistory.Entry>, day: String, vararg pkgs: String) =
    SelectionHistory.record(entries, day, pkgs.toList())

  @Test fun noEntriesMeansAllApps() {
    val s = SelectionHistory.resolve(emptyList(), "2026-11-08")
    assertTrue(s.allApps)
    assertEquals(null, s.filter())
  }

  @Test fun nullAndEmptyMeanAllApps() {
    assertTrue(SelectionHistory.resolve(SelectionHistory.record(emptyList(), "2026-11-08", null), "2026-11-08").allApps)
    assertTrue(SelectionHistory.resolve(SelectionHistory.record(emptyList(), "2026-11-08", emptyList()), "2026-11-08").allApps)
  }

  @Test fun sameDayChangeReplacesTheEntry() {
    var h = record(emptyList(), "2026-11-08", "a")
    h = record(h, "2026-11-08", "b", "c")
    assertEquals(1, h.size)
    val s = SelectionHistory.resolve(h, "2026-11-08")
    assertEquals(listOf("b", "c"), s.packages)
    assertEquals(setOf("b", "c"), s.filter())
  }

  @Test fun pastDayUsesTheOlderEntry() {
    var h = record(emptyList(), "2026-11-01", "a")
    h = record(h, "2026-11-08", "b")
    assertEquals(listOf("a"), SelectionHistory.resolve(h, "2026-11-07").packages)
    assertEquals(listOf("a"), SelectionHistory.resolve(h, "2026-11-01").packages)
    assertEquals(listOf("b"), SelectionHistory.resolve(h, "2026-11-08").packages)
    assertEquals(listOf("b"), SelectionHistory.resolve(h, "2026-12-01").packages)
  }

  @Test fun beforeTheFirstEntryIsAllApps() {
    val h = record(emptyList(), "2026-11-08", "a")
    assertTrue(SelectionHistory.resolve(h, "2026-11-07").allApps)
  }

  @Test fun switchingBackToAllAppsIsRecordedForToday() {
    var h = record(emptyList(), "2026-11-01", "a")
    h = SelectionHistory.record(h, "2026-11-08", null)
    assertEquals(listOf("a"), SelectionHistory.resolve(h, "2026-11-07").packages)
    assertTrue(SelectionHistory.resolve(h, "2026-11-08").allApps)
  }

  @Test fun listIsCappedAtForty() {
    var h = emptyList<SelectionHistory.Entry>()
    for (i in 1..45) h = record(h, "2026-10-%02d".format(i.coerceAtMost(31)).let { if (i > 31) "2026-11-%02d".format(i - 31) else it }, "p$i")
    assertEquals(SelectionHistory.MAX_ENTRIES, h.size)
    // The five oldest are gone; the newest is kept.
    assertEquals("p45", h.last().packages.single())
    assertEquals("p6", h.first().packages.single())
  }

  @Test fun jsonRoundTripAndBadJson() {
    val h = record(record(emptyList(), "2026-11-01", "a", "b"), "2026-11-08")
    assertEquals(h, SelectionHistory.fromJson(SelectionHistory.toJson(h)))
    assertEquals(emptyList<SelectionHistory.Entry>(), SelectionHistory.fromJson("not json"))
    assertEquals(emptyList<SelectionHistory.Entry>(), SelectionHistory.fromJson(null))
  }
}
