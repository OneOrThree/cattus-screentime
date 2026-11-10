/**
 * Permission state for screen time measurement.
 *
 * - iOS: Family Controls authorization for the individual (`.individual`).
 * - Android: Usage Access (`PACKAGE_USAGE_STATS`). Android has no system prompt, so
 *   `notDetermined` means the app never sent the user to the Usage Access settings,
 *   and `denied` means it did and the user did not turn it on.
 * - `unavailable`: the platform cannot measure (for example, web).
 */
export type AuthorizationStatus = 'approved' | 'denied' | 'notDetermined' | 'unavailable';

/**
 * Apps chosen with the iOS FamilyActivityPicker. iOS gives the app opaque tokens only,
 * so JavaScript sees counts, never app names.
 */
export type IosSelection = {
  platform: 'ios';
  applications: number;
  categories: number;
  webDomains: number;
};

/** Apps chosen on Android. `allApps: true` means every launchable app is measured. */
export type AndroidSelection = {
  platform: 'android';
  allApps: boolean;
  packages: string[];
};

export type Selection = IosSelection | AndroidSelection;

/**
 * How much of the day the number covers.
 *
 * - `full`: measured for the whole day (so far, for today).
 * - `partial`: measured for part of the day only. On iOS, monitoring started or the
 *   selection changed during the day. On Android, the raw event log was incomplete and
 *   the number comes from the daily usage approximation.
 * - `none`: not measured on that day. `minutes` is `null`, never `0`.
 */
export type Coverage = 'full' | 'partial' | 'none';

export type DailyUsage = {
  /** Local calendar date, `YYYY-MM-DD`. */
  date: string;
  /**
   * Foreground usage of the selected apps in minutes, or `null` when `coverage` is `none`.
   * On iOS this is a lower bound in steps of `granularityMinutes`: the real usage is at
   * least `minutes` and less than `minutes + granularityMinutes`.
   */
  minutes: number | null;
  /** 15 on iOS, 1 on Android. */
  granularityMinutes: number;
  coverage: Coverage;
};

export type InstalledApp = {
  packageName: string;
  label: string;
};

export type AppUsage = {
  packageName: string;
  label: string;
  seconds: number;
};

/** Per-app usage for one day (Android only). */
export type AppUsageBreakdown = {
  date: string;
  totalSeconds: number;
  /** Usage that is not in `apps`: launchers, system UI and other non-launchable packages. */
  otherSeconds: number;
  /** Most used first. Apps with no usage are left out. */
  apps: AppUsage[];
  coverage: Exclude<Coverage, 'none'>;
};

/**
 * Raw iOS log that the native module returns. The DeviceActivityMonitor extension writes
 * `events`; the app writes `registrations`. JavaScript turns the log into `DailyUsage`
 * with `computeIosDailyUsage`. Timestamps are Unix epoch seconds.
 */
export type IosUsageLog = {
  bucketMinutes: number;
  maxMinutes: number;
  /** Whether `DeviceActivityCenter` currently lists the usage activity. */
  monitoringActive: boolean;
  /** Ascending by `registeredAt`. */
  registrations: { registeredAt: number; signature: string }[];
  /** One entry per threshold event that the extension received. */
  events: { bucketMinutes: number; firedAt: number; registeredAt: number }[];
};

export type CattusScreenTimeErrorCode =
  | 'ERR_UNSUPPORTED_PLATFORM'
  | 'ERR_NOT_AUTHORIZED'
  | 'ERR_NO_SELECTION'
  | 'ERR_APP_GROUP_MISSING'
  | 'ERR_MONITORING_FAILED'
  | 'ERR_NO_VIEW_CONTROLLER'
  | 'ERR_USER_LOCKED'
  | 'ERR_USAGE_UNAVAILABLE';
