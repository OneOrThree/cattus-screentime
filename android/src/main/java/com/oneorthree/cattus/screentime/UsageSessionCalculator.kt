package com.oneorthree.cattus.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/** A lifecycle or device event from the system usage log, copied out of `UsageEvents.Event`. */
internal class RawEvent(
  val eventType: Int,
  val timeStamp: Long,
  val packageName: String?,
  val className: String?,
)

/** One bucket of `queryUsageStats(INTERVAL_DAILY)`. */
internal class DailyStat(
  val packageName: String,
  val firstTimeStamp: Long,
  val lastTimeStamp: Long,
  val totalTimeInForeground: Long,
)

/** Where the calculator reads usage data from. Tests replace it; production uses [ManagerSource]. */
internal interface UsageSource {
  /** Raw events in `[begin, end)`, in time order. Throws [UsageUnavailableException] if the system gives none. */
  fun events(begin: Long, end: Long): List<RawEvent>

  /** Daily usage buckets that overlap `[begin, end]`. Throws [UsageUnavailableException] if unavailable. */
  fun dailyStats(begin: Long, end: Long): List<DailyStat>
}

/** The system could not return usage data (not a permission problem). */
internal class UsageUnavailableException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

internal class ManagerSource(private val manager: UsageStatsManager) : UsageSource {
  override fun events(begin: Long, end: Long): List<RawEvent> {
    val events = manager.queryEvents(begin, end)
      ?: throw UsageUnavailableException("queryEvents returned null")
    val out = ArrayList<RawEvent>()
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      out.add(RawEvent(event.eventType, event.timeStamp, event.packageName, event.className))
    }
    return out
  }

  override fun dailyStats(begin: Long, end: Long): List<DailyStat> {
    val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
      ?: throw UsageUnavailableException("queryUsageStats returned null")
    return stats.map {
      DailyStat(it.packageName, it.firstTimeStamp, it.lastTimeStamp, it.totalTimeInForeground)
    }
  }
}

/**
 * Rebuilds foreground usage from the raw event stream of `UsageStatsManager.queryEvents`.
 *
 * `queryUsageStats(INTERVAL_DAILY)` buckets shift with device quirks (reboots, for example),
 * so it is only a fallback for days whose raw events the system no longer keeps.
 *
 * Edge cases:
 *  1. Screen off. A SCREEN_NON_INTERACTIVE event closes every open session. Without this, a
 *     phone that sleeps with an app in front counts the whole night.
 *  2. A session across midnight. Events are read from [LOOKBACK_MS] before `begin`, but time is
 *     added only inside `[begin, end)`. A session that was resumed the evening before and is
 *     still running counts only its part of the requested day, so each day gets its own share.
 *  3. Split screen. Two different apps resumed at the same time each count their own time,
 *     so the overlap counts once per app (the same definition as the iOS Screen Time app).
 *     Several activities of the same app that overlap (multi-instance, picture-in-picture)
 *     are merged per package and count once.
 *  4. Event retention. The system keeps raw events for a few days only (it varies by device).
 *     If no app lifecycle event exists in the range, the log does not cover it and the daily
 *     statistics fallback is used.
 *  5. Reboot. DEVICE_SHUTDOWN closes open sessions. A gap in events after a reboot can make
 *     the value smaller; the caller must not treat a lower value as an error.
 *
 * Accepted limit: a session that started more than [LOOKBACK_MS] before `begin` and left no
 * event until `end` (24 hours or more in the foreground with no screen-off and no app switch)
 * gives no signal and is missed. The screen-off event makes this practically impossible.
 * Comparing every result with the fallback would avoid it, but the fallback buckets can
 * overcount, so it is not done.
 */
internal object UsageSessionCalculator {

  /**
   * How far before `begin` events are read, to catch a session that was resumed before the
   * range and keeps running. 24 hours covers the whole previous day, so even a long session
   * with no end event (one side of a split screen, say) still shows its starting RESUMED event.
   * Event retention is a few days, so a 24-hour lookback is safe.
   */
  private const val LOOKBACK_MS = 24L * 60 * 60 * 1000

  /** Per-app milliseconds from one scan; `totalMillis` is exactly their sum. */
  data class Breakdown(val totalMillis: Long, val byPackage: Map<String, Long>)

  /**
   * What a scan found, plus the signals that tell how far the numbers can be trusted.
   * Total and per-app values come from the same scan and the same union merge, so they
   * cannot disagree (for example "1 hour in total, 55 minutes when the apps are added").
   */
  private class ScanResult(
    val byPackage: Map<String, Long>,
    val sawLifecycleEventInRange: Boolean,
    val sawUnmatchedTerminalInRange: Boolean,
    val sawMissingPause: Boolean,
  )

  fun confirmedBreakdown(manager: UsageStatsManager, selection: Set<String>?, begin: Long, end: Long): Breakdown? =
    confirmedBreakdown(ManagerSource(manager), selection, begin, end)

  fun foregroundMillis(manager: UsageStatsManager, selection: Set<String>?, begin: Long, end: Long): Long =
    foregroundMillis(ManagerSource(manager), selection, begin, end)

  fun foregroundBreakdown(manager: UsageStatsManager, selection: Set<String>?, begin: Long, end: Long): Breakdown =
    foregroundBreakdown(ManagerSource(manager), selection, begin, end)

  /**
   * The rebuilt usage in `[begin, end)`, or `null` when the raw log cannot be trusted:
   * no app lifecycle event in the range (retention), an end event whose start was not seen,
   * or a RESUMED event for an activity that was already open (a missing PAUSED event).
   * `null` is never `0`: callers fall back to the approximation and mark the result partial.
   */
  fun confirmedBreakdown(source: UsageSource, selection: Set<String>?, begin: Long, end: Long): Breakdown? {
    if (end <= begin) return null
    val result = scan(source, selection, begin, end)
    if (!result.sawLifecycleEventInRange || result.sawUnmatchedTerminalInRange || result.sawMissingPause) return null
    return Breakdown(result.byPackage.values.sum(), result.byPackage)
  }

  /**
   * Total foreground milliseconds in `[begin, end)`. An empty or null [selection] measures all apps.
   */
  fun foregroundMillis(source: UsageSource, selection: Set<String>?, begin: Long, end: Long): Long {
    if (end <= begin) return 0L
    val scan = scan(source, selection, begin, end)
    val totalMs = scan.byPackage.values.sum()

    // An end event without its start means a session is lost, so the rebuilt total is a
    // partial sum. Compare it with the fallback and take the larger. The fallback can also be
    // smaller (bucket edges shift), which is why this is a max.
    if (scan.sawUnmatchedTerminalInRange) {
      return maxOf(totalMs, dailyStatsFallbackByPackage(source, selection, begin, end).values.sum())
    }
    // Edge 4: no lifecycle event in the range means the log does not cover it. A day without
    // phone use also lands here, but then the fallback is 0 too, so the result is the same.
    if (!scan.sawLifecycleEventInRange && totalMs == 0L) {
      return dailyStatsFallbackByPackage(source, selection, begin, end).values.sum()
    }
    return totalMs
  }

  /**
   * Total and per-app milliseconds from a single scan, with the same fallback rules as
   * [foregroundMillis].
   *
   * Calling total and per-app separately would give each its own scan and its own "now", so a
   * second of new usage between the calls shows up as a false "other apps" row.
   */
  fun foregroundBreakdown(source: UsageSource, selection: Set<String>?, begin: Long, end: Long): Breakdown {
    if (end <= begin) return Breakdown(0L, emptyMap())
    val scan = scan(source, selection, begin, end)
    val reconstructed = scan.byPackage.values.sum()

    if (scan.sawUnmatchedTerminalInRange) {
      val fallback = dailyStatsFallbackByPackage(source, selection, begin, end)
      val fallbackSum = fallback.values.sum()
      // Pick one map as a whole. Mixing the two per package can exceed the total:
      // scan A=20 min, fallback A=15 and B=10 give a total of 25 minutes, but the mix
      // A=20 + B=10 is 30. The total of the chosen map always equals the reported total.
      return if (fallbackSum > reconstructed) Breakdown(fallbackSum, fallback)
      else Breakdown(reconstructed, scan.byPackage)
    }
    if (scan.byPackage.isEmpty() && !scan.sawLifecycleEventInRange) {
      val fallback = dailyStatsFallbackByPackage(source, selection, begin, end)
      return Breakdown(fallback.values.sum(), fallback)
    }
    return Breakdown(reconstructed, scan.byPackage)
  }

  private fun scan(source: UsageSource, selection: Set<String>?, begin: Long, end: Long): ScanResult {
    val events = source.events(begin - LOOKBACK_MS, end)

    // Open sessions are tracked per "package/activity", not per package. With a per-package
    // map, "A paused, B resumed, A stopped" lets A's STOPPED close B's open session and the
    // rest of B's use is lost. A switch inside one app is PAUSED then RESUMED, so nothing is
    // counted twice.
    val openedAt = HashMap<String, Long>() // "package/class" -> RESUMED time
    // Keys that had a RESUMED in this scan. Tells a normal "STOPPED after PAUSED" apart from
    // a terminal event whose start we never saw.
    val everOpened = HashSet<String>()
    // Closed sessions are not summed at once. They are collected per package as [start, end)
    // and merged at the end, because several activities of one package that are open together
    // would add the same wall-clock time once per activity. Different packages are summed
    // separately (split screen, edge 3).
    val closedByPkg = HashMap<String, MutableList<LongArray>>()
    var sawLifecycleEventInRange = false
    // A session that began more than LOOKBACK_MS before `begin` and ended in range: its
    // RESUMED is outside the query, so close() finds nothing to add and the day's share is
    // lost. Treated as incomplete coverage.
    var sawUnmatchedTerminalInRange = false
    var sawMissingPause = false

    // Close a session: clip to [begin, end) (this splits a session across midnight by day)
    // and add it to the package list. Keys are "package/class" and package names have no '/'.
    fun close(key: String, at: Long) {
      val started = openedAt.remove(key) ?: return
      val clippedStart = maxOf(started, begin)
      val clippedEnd = minOf(at, end)
      if (clippedEnd > clippedStart) {
        closedByPkg
          .getOrPut(key.substringBefore('/')) { mutableListOf() }
          .add(longArrayOf(clippedStart, clippedEnd))
      }
    }

    for (event in events) {
      // The log counts as covering the range only when an app lifecycle event (the ones used to
      // rebuild sessions) lies inside it. Unrelated events (user interaction, configuration
      // change) can exist for a range that starts in the middle of a long session, and the
      // fallback must stay alive there. SCREEN_NON_INTERACTIVE and other device-wide events
      // happen without app use, so they are not evidence either.
      val isLifecycleEvent =
        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
          event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
          event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
      if (isLifecycleEvent && event.timeStamp >= begin && event.timeStamp < end) sawLifecycleEventInRange = true
      when (event.eventType) {
        // ACTIVITY_RESUMED is the old MOVE_TO_FOREGROUND (value 1), so devices below API 29 work.
        UsageEvents.Event.ACTIVITY_RESUMED -> {
          val pkg = event.packageName ?: continue
          if (!selection.isNullOrEmpty() && pkg !in selection) continue
          // Overwrite: after a missing close event, a second RESUMED restarts the session.
          // That undercounts rather than overcounts. It also marks the scan as untrusted.
          val key = "$pkg/${event.className}"
          if (key in openedAt && event.timeStamp >= begin) sawMissingPause = true
          openedAt[key] = event.timeStamp
          everOpened.add(key)
        }
        // ACTIVITY_PAUSED is the old MOVE_TO_BACKGROUND (value 2). A STOPPED (23) after a
        // PAUSED is harmless: close() removes the key, so the second one finds nothing.
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED,
        -> {
          val pkg = event.packageName
          val key = "$pkg/${event.className}"
          if (
            pkg != null &&
            key !in openedAt &&
            key !in everOpened &&
            (selection.isNullOrEmpty() || pkg in selection) &&
            event.timeStamp >= begin && event.timeStamp < end
          ) {
            sawUnmatchedTerminalInRange = true
          }
          close(key, event.timeStamp)
        }
        // Screen off and device shutdown close every open session (edges 1 and 5).
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.DEVICE_SHUTDOWN,
        -> {
          val at = event.timeStamp
          openedAt.keys.toList().forEach { close(it, at) }
        }
      }
    }
    // Sessions still open (in use right now) end at `end`.
    openedAt.keys.toList().forEach { close(it, end) }

    // Union of the intervals of each package: sort by start; if the next interval starts at or
    // before the end of the current merged one (overlap or touch) extend the end, otherwise
    // finish the merged interval and start a new one.
    // Check: nested [10,100)+[20,50) -> 90, touching [10,20)+[20,30) -> 20,
    // separate [10,20)+[30,40) -> 10+10.
    val byPackage = HashMap<String, Long>(closedByPkg.size)
    for ((pkg, intervals) in closedByPkg) {
      intervals.sortBy { it[0] }
      var pkgMs = 0L
      var mergedStart = intervals[0][0]
      var mergedEnd = intervals[0][1]
      for (i in 1 until intervals.size) {
        val next = intervals[i]
        if (next[0] <= mergedEnd) {
          if (next[1] > mergedEnd) mergedEnd = next[1]
        } else {
          pkgMs += mergedEnd - mergedStart
          mergedStart = next[0]
          mergedEnd = next[1]
        }
      }
      pkgMs += mergedEnd - mergedStart
      // Apps with 0 ms are left out: rows of "0 min" would bury the apps that were used.
      if (pkgMs > 0L) byPackage[pkg] = pkgMs
    }

    return ScanResult(byPackage, sawLifecycleEventInRange, sawUnmatchedTerminalInRange, sawMissingPause)
  }

  /**
   * Approximation from `queryUsageStats(INTERVAL_DAILY)`: the foreground time of every daily
   * bucket that overlaps the range, per package. Bucket edges can shift, so this is only an
   * approximation. The total fallback sums this map, so both use the same filter and bucket test.
   */
  private fun dailyStatsFallbackByPackage(
    source: UsageSource,
    selection: Set<String>?,
    begin: Long,
    end: Long,
  ): Map<String, Long> {
    val byPackage = HashMap<String, Long>()
    for (stat in source.dailyStats(begin, end - 1)) {
      if (!selection.isNullOrEmpty() && stat.packageName !in selection) continue
      // Skip buckets that do not overlap the range (a neighbour day pulled in by shifted edges).
      if (stat.lastTimeStamp <= begin || stat.firstTimeStamp >= end) continue
      if (stat.totalTimeInForeground <= 0L) continue
      // Several buckets of one package can overlap, so add up (assigning keeps only the last).
      byPackage[stat.packageName] = (byPackage[stat.packageName] ?: 0L) + stat.totalTimeInForeground
    }
    return byPackage
  }
}
