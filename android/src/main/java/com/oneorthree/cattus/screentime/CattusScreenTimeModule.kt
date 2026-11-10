package com.oneorthree.cattus.screentime

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream

/**
 * Android side of CattusScreenTime.
 *
 * Differences from iOS (see docs/architecture.md):
 *  - Usage is read straight from `UsageStatsManager`; no extension or scheduled thresholds.
 *  - Usage Access is a special permission with no system prompt. The user turns it on in
 *    Settings, and the module checks again when the app returns to the foreground.
 *  - Android has no `notDetermined` state of its own, so a local flag ("the user was sent to
 *    Settings") tells `notDetermined` from `denied`.
 */
class CattusScreenTimeModule : Module() {
  companion object {
    private const val PREFS_NAME = "cattus_screentime"

    // Set when the user is sent to Settings: `notDetermined` (never sent) vs `denied` (sent, still off).
    private const val KEY_SENT_TO_SETTINGS = "sentToUsageAccessSettings"

    // JSON list of SelectionHistory entries.
    private const val KEY_SELECTION_HISTORY = "selectionHistory"

    private const val ICON_PX = 96
    private const val MAX_DAILY_USAGE_DAYS = 30
    private const val MIN_APP_USAGE_OFFSET = -29
  }

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val prefs: SharedPreferences
    get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // A requestAuthorization call that waits for the user to come back from Settings.
  private var pendingAuthPromise: Promise? = null

  override fun definition() = ModuleDefinition {
    Name("CattusScreenTime")

    AsyncFunction("getAuthorizationStatus") {
      currentStatus()
    }

    // There is no system prompt: open the Usage Access list and resolve with the status once
    // the app is back in the foreground (OnActivityEntersForeground below).
    AsyncFunction("requestAuthorization") { promise: Promise ->
      if (isUsageAccessGranted()) {
        promise.resolve(STATUS_APPROVED)
        return@AsyncFunction
      }
      // Record that the user was sent to Settings before opening it: from now on "off" means denied.
      prefs.edit().putBoolean(KEY_SENT_TO_SETTINGS, true).apply()
      // A request that is still waiting (a double tap, say) is answered with the current status
      // and replaced by this one.
      pendingAuthPromise?.resolve(currentStatus())
      pendingAuthPromise = promise
      try {
        openUsageAccessSettingsScreen()
      } catch (_: Exception) {
        // A device that cannot open the screen: answer with the current status.
        pendingAuthPromise = null
        promise.resolve(currentStatus())
      }
    }

    AsyncFunction("getSelection") {
      selectionMap(SelectionHistory.resolve(loadHistory(), DayBounds.dateKey(System.currentTimeMillis())))
    }

    AsyncFunction("reset") {
      pendingAuthPromise?.resolve(currentStatus())
      pendingAuthPromise = null
      prefs.edit().clear().apply()
    }

    // Always opens the Usage Access list, even when access is on, so the user can turn it off.
    // requestAuthorization returns at once when access is on, and the app's own settings page
    // has no Usage Access toggle. Returns false if no activity can handle the intent.
    AsyncFunction("openUsageAccessSettings") {
      try {
        openUsageAccessSettingsScreen()
        true
      } catch (_: Exception) {
        false
      }
    }

    // Launchable apps without this app, sorted by label. Icons are separate (getAppIcon):
    // encoding the bitmaps of 100+ apps at once would delay the first paint of the list.
    AsyncFunction("getInstalledApps") {
      launchablePackages()
        .asSequence()
        .filter { it != context.packageName }
        .map { pkg -> mapOf("packageName" to pkg, "label" to appLabel(pkg)) }
        // Sort by label; package-name order looks random to a user.
        .sortedBy { it["label"]?.lowercase() }
        .toList()
    }

    // One app icon as base64 PNG without a data-URI prefix. A failure (an uninstalled app, for
    // example) gives null, not an exception: one icon must not break the list.
    AsyncFunction("getAppIcon") { packageName: String ->
      runCatching { encodeIcon(context.packageManager.getApplicationIcon(packageName)) }.getOrNull()
    }

    // `null` or an empty list means all apps. An empty set would be read as "no app matches"
    // and report zero minutes, so it is stored as "all apps" instead.
    AsyncFunction("setSelectedPackages") { packages: List<String>? ->
      val today = DayBounds.dateKey(System.currentTimeMillis())
      val updated = SelectionHistory.record(loadHistory(), today, packages)
      prefs.edit().putString(KEY_SELECTION_HISTORY, SelectionHistory.toJson(updated)).apply()
      selectionMap(SelectionHistory.resolve(updated, today))
    }

    // `days` local days, oldest first, today last. A past day uses the selection of that day.
    AsyncFunction("getDailyUsage") { days: Int ->
      if (days < 1 || days > MAX_DAILY_USAGE_DAYS) {
        throw InvalidArgumentException("days must be between 1 and $MAX_DAILY_USAGE_DAYS, got $days")
      }
      val manager = readyUsageManager()
      val now = System.currentTimeMillis()
      val history = loadHistory()
      val result = (days - 1 downTo 0).map { back ->
        val day = DayBounds.of(now, -back)
        val selection = SelectionHistory.resolve(history, day.date).filter()
        val confirmed = usageGuard {
          UsageSessionCalculator.confirmedBreakdown(manager, selection, day.begin, day.end)
        }
        val minutes: Long
        val coverage: String
        if (confirmed != null) {
          minutes = confirmed.totalMillis / 60_000L
          coverage = COVERAGE_FULL
        } else {
          // The raw log does not cover this day (retention, a lost event): use the daily
          // statistics approximation and mark it partial.
          minutes = usageGuard {
            UsageSessionCalculator.foregroundMillis(manager, selection, day.begin, day.end)
          } / 60_000L
          coverage = COVERAGE_PARTIAL
        }
        mapOf("date" to day.date, "minutes" to minutes.toInt(), "coverage" to coverage)
      }
      // Access can be switched off while the queries run; the numbers would then be stale or empty.
      if (!isUsageAccessGranted()) throw NotAuthorizedException()
      result
    }

    // Usage of one day per app. dayOffset: 0 = today (midnight to now), -1 = yesterday, down to -29.
    AsyncFunction("getAppUsage") { dayOffset: Int ->
      if (dayOffset > 0 || dayOffset < MIN_APP_USAGE_OFFSET) {
        throw InvalidArgumentException("dayOffset must be between $MIN_APP_USAGE_OFFSET and 0, got $dayOffset")
      }
      val manager = readyUsageManager()
      val day = DayBounds.of(System.currentTimeMillis(), dayOffset)
      val selection = SelectionHistory.resolve(loadHistory(), day.date).filter()

      val confirmed = usageGuard {
        UsageSessionCalculator.confirmedBreakdown(manager, selection, day.begin, day.end)
      }
      val breakdown = confirmed ?: usageGuard {
        UsageSessionCalculator.foregroundBreakdown(manager, selection, day.begin, day.end)
      }
      if (!isUsageAccessGranted()) throw NotAuthorizedException()

      // Only launchable apps are listed. UsageStats also holds the home launcher and system UI;
      // listing them would make time on the home screen look like app use and leave rows
      // that have no readable label. It is the same rule as the app picker's list.
      val launchable = launchablePackages()
      val listed = breakdown.byPackage.filterKeys { it in launchable }
      mapOf(
        "date" to day.date,
        "totalSeconds" to (breakdown.totalMillis / 1000L).toInt(),
        // Subtract in milliseconds, then round down. Rounding each app first would drop up to
        // 999 ms per app and invent time (61 apps lose a full minute).
        "otherSeconds" to ((breakdown.totalMillis - listed.values.sum()).coerceAtLeast(0L) / 1000L).toInt(),
        "apps" to listed.entries.sortedByDescending { it.value }.map {
          mapOf(
            "packageName" to it.key,
            "label" to appLabel(it.key),
            // Seconds, not minutes: with minutes, all use under one minute would show as "0".
            "seconds" to (it.value / 1000L).toInt(),
          )
        },
        "coverage" to if (confirmed != null) COVERAGE_FULL else COVERAGE_PARTIAL,
      )
    }

    OnDestroy {
      pendingAuthPromise?.resolve(currentStatus())
      pendingAuthPromise = null
    }

    // The user returns from Settings: answer the waiting request with the real AppOps state.
    OnActivityEntersForeground {
      pendingAuthPromise?.let { promise ->
        pendingAuthPromise = null
        promise.resolve(currentStatus())
      }
    }
  }

  private fun loadHistory(): List<SelectionHistory.Entry> =
    SelectionHistory.fromJson(prefs.getString(KEY_SELECTION_HISTORY, null))

  private fun selectionMap(selection: SelectionHistory.Selection): Map<String, Any> =
    mapOf("allApps" to selection.allApps, "packages" to selection.packages)

  /**
   * Checks Usage Access and the user lock state, and returns the manager. Both checks happen
   * before any query so the caller gets a specific error instead of a generic failure.
   */
  private fun readyUsageManager(): UsageStatsManager {
    if (!isUsageAccessGranted()) throw NotAuthorizedException()
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    if (!userManager.isUserUnlocked) throw UserLockedException()
    return context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
  }

  /**
   * Runs a usage query and maps its failures to coded errors: a revoked permission to
   * ERR_NOT_AUTHORIZED, anything else the system throws or returns empty to ERR_USAGE_UNAVAILABLE.
   */
  private fun <T> usageGuard(block: () -> T): T = try {
    block()
  } catch (e: SecurityException) {
    throw NotAuthorizedException()
  } catch (e: UsageUnavailableException) {
    throw UsageUnavailableCodedException(e)
  } catch (e: RuntimeException) {
    throw UsageUnavailableCodedException(e)
  }

  /**
   * Package names of apps that have a launcher entry: what a user counts as "an app".
   * The app picker and the usage breakdown share this rule, so they cannot disagree.
   *
   * On Android 11+ this needs a `<queries>` block with the MAIN/LAUNCHER intent in the
   * manifest (this library declares it). Without it the list is almost empty.
   */
  private fun launchablePackages(): Set<String> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(launcherIntent, 0)
    }
    // An app with several launcher activities appears more than once, so collect into a set.
    return resolved.mapTo(HashSet()) { it.activityInfo.packageName }
  }

  // The display name, or the package name if it cannot be read: better than dropping the row.
  private fun appLabel(packageName: String): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.getApplicationInfo(packageName, 0)
    }
    pm.getApplicationLabel(info).toString()
  }.getOrDefault(packageName)

  // Icon to base64 PNG. Adaptive icons are not Bitmaps, so draw the Drawable on a canvas
  // (casting to BitmapDrawable breaks for them).
  private fun encodeIcon(drawable: Drawable): String {
    val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    bitmap.recycle()
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
  }

  private fun currentStatus(): String = when {
    isUsageAccessGranted() -> STATUS_APPROVED
    prefs.getBoolean(KEY_SENT_TO_SETTINGS, false) -> "denied"
    else -> "notDetermined"
  }

  // Usage Access via AppOpsManager (OPSTR_GET_USAGE_STATS).
  private fun isUsageAccessGranted(): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
      @Suppress("DEPRECATION")
      appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    // MODE_DEFAULT means AppOps has no record: decide by whether the manifest permission is held.
    return if (mode == AppOpsManager.MODE_DEFAULT) {
      context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      mode == AppOpsManager.MODE_ALLOWED
    }
  }

  // Opens the full Usage Access list. A deep link to this app's own entry (package: URI) is
  // not documented and crashes or shows an empty screen on some vendors, so the list is used.
  private fun openUsageAccessSettingsScreen() {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    val activity = appContext.currentActivity
    if (activity != null) {
      activity.startActivity(intent)
    } else {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
  }
}

private const val STATUS_APPROVED = "approved"
private const val COVERAGE_FULL = "full"
private const val COVERAGE_PARTIAL = "partial"
