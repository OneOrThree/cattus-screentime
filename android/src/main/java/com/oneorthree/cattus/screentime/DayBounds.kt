package com.oneorthree.cattus.screentime

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One local calendar day as a half-open time range `[begin, end)` in epoch milliseconds. */
internal data class DayBounds(val date: String, val begin: Long, val end: Long) {
  companion object {
    /**
     * The local day that is [dayOffset] days from the day of [now] (0 = today, -1 = yesterday).
     *
     * Midnights come from `Calendar` arithmetic in [timeZone], not from fixed 24-hour offsets,
     * so a day with a daylight-saving change is 23 or 25 hours long. For today, `end` is [now]
     * because the day is not over; for a past day it is the next local midnight.
     */
    fun of(now: Long, dayOffset: Int, timeZone: TimeZone = TimeZone.getDefault()): DayBounds {
      val start = midnight(now, dayOffset, timeZone)
      val end = if (dayOffset >= 0) now else midnight(now, dayOffset + 1, timeZone)
      return DayBounds(dateKey(start, timeZone), start, end)
    }

    /** The local date key (`YYYY-MM-DD`) of the instant [millis]. */
    fun dateKey(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
      val c = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
      return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
      )
    }

    private fun midnight(now: Long, dayOffset: Int, timeZone: TimeZone): Long {
      val c = Calendar.getInstance(timeZone)
      c.timeInMillis = now
      c.add(Calendar.DAY_OF_YEAR, dayOffset)
      c.set(Calendar.HOUR_OF_DAY, 0)
      c.set(Calendar.MINUTE, 0)
      c.set(Calendar.SECOND, 0)
      c.set(Calendar.MILLISECOND, 0)
      return c.timeInMillis
    }
  }
}
