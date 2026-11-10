import { Platform } from 'react-native';

import type {
  AndroidSelection,
  AppUsageBreakdown,
  AuthorizationStatus,
  DailyUsage,
  InstalledApp,
  IosSelection,
  IosUsageLog,
  Selection,
} from './CattusScreenTime.types';
import NativeModule from './CattusScreenTimeModule';
import { CattusScreenTimeError } from './errors';
import { computeIosDailyUsage } from './iosUsage';

export * from './CattusScreenTime.types';
export { CattusScreenTimeError, hasErrorCode } from './errors';
export { computeIosDailyUsage, localDateKey } from './iosUsage';

/** iOS reports usage in steps of this many minutes. */
export const IOS_BUCKET_MINUTES = 15;
/** iOS stops counting a day at this many minutes. */
export const IOS_MAX_MINUTES = 900;
/** Largest `days` value that `getDailyUsage` accepts. */
export const MAX_DAYS = 30;

type NativePlatform = 'ios' | 'android';

/** Whether this platform can measure screen time with this module. */
export function isSupported(): boolean {
  return NativeModule != null && (Platform.OS === 'ios' || Platform.OS === 'android');
}

function native(only?: NativePlatform, name?: string) {
  if (!isSupported() || NativeModule == null) {
    throw new CattusScreenTimeError(
      'ERR_UNSUPPORTED_PLATFORM',
      `cattus-screentime is not available on ${Platform.OS}. Check that the native module is installed and the app was rebuilt.`
    );
  }
  if (only && Platform.OS !== only) {
    throw new CattusScreenTimeError(
      'ERR_UNSUPPORTED_PLATFORM',
      `${name ?? 'This function'} is only available on ${only === 'ios' ? 'iOS' : 'Android'}.`
    );
  }
  return NativeModule;
}

function checkDays(days: number): void {
  if (!Number.isInteger(days) || days < 1 || days > MAX_DAYS) {
    throw new RangeError(`days must be an integer from 1 to ${MAX_DAYS}, got ${days}.`);
  }
}

// ── Permission ───────────────────────────────────────────────────────────────

/** Current permission state. `unavailable` on platforms the module does not support. */
export async function getAuthorizationStatus(): Promise<AuthorizationStatus> {
  if (!isSupported()) return 'unavailable';
  return native().getAuthorizationStatus();
}

/**
 * Asks for permission and resolves with the state afterwards.
 *
 * - iOS: shows the Family Controls prompt. Cancelling resolves the current state.
 * - Android: opens the Usage Access list in Settings and resolves when the user comes back
 *   to the app. Resolves at once if access is already on.
 */
export async function requestAuthorization(): Promise<AuthorizationStatus> {
  return native().requestAuthorization();
}

/**
 * Android only: opens the Usage Access list even when access is on, so that the user can
 * turn it off. Resolves `false` if the device has no such settings screen.
 */
export async function openUsageAccessSettings(): Promise<boolean> {
  return native('android', 'openUsageAccessSettings').openUsageAccessSettings();
}

// ── Selection ────────────────────────────────────────────────────────────────

/**
 * The apps being measured. iOS returns `null` until the user picks apps. Android returns
 * `{ allApps: true }` until a selection is saved.
 */
export async function getSelection(): Promise<Selection | null> {
  const selection = await native().getSelection();
  if (selection == null) return null;
  if (Platform.OS === 'ios')
    return { platform: 'ios', ...(selection as Omit<IosSelection, 'platform'>) };
  return { platform: 'android', ...(selection as Omit<AndroidSelection, 'platform'>) };
}

/**
 * iOS only: shows the system app picker. On Done, saves the selection and starts
 * monitoring it; resolves with the selection counts. Resolves `null` on Cancel.
 */
export async function presentAppPicker(): Promise<IosSelection | null> {
  const selection = await native('ios', 'presentAppPicker').presentAppPicker();
  return selection == null ? null : { platform: 'ios', ...selection };
}

/**
 * Makes sure the saved selection is being monitored. Call it when the app starts: it does
 * nothing if monitoring already runs with the same selection, and registers again if iOS
 * dropped it (for example after the permission was turned off and on).
 *
 * Resolves `false` on iOS when no apps were picked yet. Android needs no monitoring and
 * resolves `true`.
 */
export async function startMonitoring(): Promise<boolean> {
  if (Platform.OS === 'android' && isSupported()) return true;
  return native('ios', 'startMonitoring').startMonitoring();
}

/** Android only: launchable apps on the device, this app excluded, sorted by name. */
export async function getInstalledApps(): Promise<InstalledApp[]> {
  return native('android', 'getInstalledApps').getInstalledApps();
}

/** Android only: the app icon as a base64 PNG without a data-URI prefix, or `null`. */
export async function getAppIcon(packageName: string): Promise<string | null> {
  return native('android', 'getAppIcon').getAppIcon(packageName);
}

/**
 * Android only: saves the apps to measure. `'all'` measures every launchable app. The
 * change applies to today; earlier days keep the selection that was in effect on them.
 */
export async function setSelectedPackages(packages: string[] | 'all'): Promise<AndroidSelection> {
  const saved = await native('android', 'setSelectedPackages').setSelectedPackages(
    packages === 'all' ? null : packages
  );
  return { platform: 'android', ...saved };
}

// ── Usage ────────────────────────────────────────────────────────────────────

/**
 * Usage of the selected apps for the last `days` local days, oldest first, today last.
 *
 * Rejects with code `ERR_NOT_AUTHORIZED` when permission is off. A day that was not
 * measured has `coverage: 'none'` and `minutes: null`; it is never reported as 0.
 */
export async function getDailyUsage(options: { days?: number } = {}): Promise<DailyUsage[]> {
  const days = options.days ?? 7;
  checkDays(days);
  const module = native();

  if (Platform.OS === 'ios') {
    const status = await module.getAuthorizationStatus();
    if (status !== 'approved') {
      throw new CattusScreenTimeError(
        'ERR_NOT_AUTHORIZED',
        'Screen Time permission is not approved. Call requestAuthorization() first.'
      );
    }
    const log = await module.getUsageLog();
    return computeIosDailyUsage(log, { now: Date.now(), days });
  }

  const rows = await module.getDailyUsage(days);
  return rows.map((row) => ({ ...row, granularityMinutes: 1 }));
}

/**
 * iOS only: the raw log behind `getDailyUsage`, for debugging or for your own computation
 * with `computeIosDailyUsage`. Timestamps are Unix epoch seconds.
 */
export async function getIosUsageLog(): Promise<IosUsageLog> {
  return native('ios', 'getIosUsageLog').getUsageLog();
}

/**
 * Android only: per-app usage for one day. `dayOffset` 0 is today, -1 yesterday, down to
 * -29. iOS does not give apps usage numbers; show a DeviceActivityReport instead.
 */
export async function getAppUsage(
  options: { dayOffset?: number } = {}
): Promise<AppUsageBreakdown> {
  const dayOffset = options.dayOffset ?? 0;
  if (!Number.isInteger(dayOffset) || dayOffset > 0 || dayOffset < -(MAX_DAYS - 1)) {
    throw new RangeError(
      `dayOffset must be an integer from -${MAX_DAYS - 1} to 0, got ${dayOffset}.`
    );
  }
  return native('android', 'getAppUsage').getAppUsage(dayOffset);
}

/** Stops monitoring and deletes everything this module stored on the device. */
export async function reset(): Promise<void> {
  return native().reset();
}
