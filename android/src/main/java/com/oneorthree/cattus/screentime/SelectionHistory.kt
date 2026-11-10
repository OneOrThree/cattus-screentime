package com.oneorthree.cattus.screentime

import org.json.JSONArray
import org.json.JSONObject

/**
 * The measured-app selection over time. A past day is measured with the selection that was in
 * effect on that day, so changing the selection today does not rewrite yesterday's numbers.
 *
 * Pure Kotlin (no Android framework types) so JVM unit tests can run it.
 */
internal object SelectionHistory {
  /** Keep this many entries; older ones are dropped. */
  const val MAX_ENTRIES = 40

  /** The selection that starts on local date [from] (`YYYY-MM-DD`). `allApps` ignores [packages]. */
  data class Entry(val from: String, val allApps: Boolean, val packages: List<String>)

  /** Resolved selection for one day. `allApps = true` means every app is measured. */
  data class Selection(val allApps: Boolean, val packages: List<String>) {
    /** Package filter for [UsageSessionCalculator]: `null` means every app. */
    fun filter(): Set<String>? = if (allApps) null else packages.toSet()
  }

  val ALL_APPS = Selection(allApps = true, packages = emptyList())

  /** The selection in effect on [day]: the last entry whose `from` is on or before it, else all apps. */
  fun resolve(entries: List<Entry>, day: String): Selection {
    // ISO dates sort the same as calendar order, so a string comparison is enough.
    val entry = entries.lastOrNull { it.from <= day } ?: return ALL_APPS
    return Selection(entry.allApps, entry.packages)
  }

  /**
   * Record a new selection that starts on [today]. `null` or an empty list means all apps
   * (an empty filter would read as "no app matches" and report zero minutes).
   * An entry that already starts on [today] is replaced, so several changes in one day leave
   * one entry; otherwise the entry is appended. Only the last [MAX_ENTRIES] are kept.
   */
  fun record(entries: List<Entry>, today: String, packages: List<String>?): List<Entry> {
    val distinct = packages?.distinct().orEmpty()
    val entry = Entry(today, allApps = distinct.isEmpty(), packages = distinct.sorted())
    val kept = entries.filter { it.from != today } + entry
    // Dates stay in order when the clock moves forward; sort anyway so resolve's
    // "last entry on or before" holds after a clock correction.
    return kept.sortedBy { it.from }.takeLast(MAX_ENTRIES)
  }

  fun toJson(entries: List<Entry>): String {
    val array = JSONArray()
    for (e in entries) {
      array.put(
        JSONObject()
          .put("from", e.from)
          .put("allApps", e.allApps)
          .put("packages", JSONArray(e.packages)),
      )
    }
    return array.toString()
  }

  /** Parse stored JSON. Anything unreadable gives an empty list, which means all apps. */
  fun fromJson(json: String?): List<Entry> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
      val array = JSONArray(json)
      (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val packages = o.getJSONArray("packages")
        Entry(
          from = o.getString("from"),
          allApps = o.getBoolean("allApps"),
          packages = (0 until packages.length()).map { packages.getString(it) },
        )
      }
    } catch (_: org.json.JSONException) {
      emptyList()
    }
  }
}
