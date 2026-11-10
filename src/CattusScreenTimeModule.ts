import { NativeModule, requireOptionalNativeModule } from 'expo';

import type {
  AuthorizationStatus,
  Coverage,
  InstalledApp,
  IosUsageLog,
} from './CattusScreenTime.types';

export type NativeIosSelection = { applications: number; categories: number; webDomains: number };
export type NativeAndroidSelection = { allApps: boolean; packages: string[] };

export type NativeDailyUsage = { date: string; minutes: number | null; coverage: Coverage };

export type NativeAppUsageBreakdown = {
  date: string;
  totalSeconds: number;
  otherSeconds: number;
  apps: { packageName: string; label: string; seconds: number }[];
  coverage: Exclude<Coverage, 'none'>;
};

/**
 * Functions that the native module `CattusScreenTime` exposes. iOS and Android expose
 * different sets; `index.ts` checks the platform before it calls one.
 */
declare class CattusScreenTimeNativeModule extends NativeModule<Record<string, never>> {
  // Both platforms
  getAuthorizationStatus(): Promise<AuthorizationStatus>;
  requestAuthorization(): Promise<AuthorizationStatus>;
  reset(): Promise<void>;

  // iOS
  getSelection(): Promise<NativeIosSelection | NativeAndroidSelection | null>;
  presentAppPicker(): Promise<NativeIosSelection | null>;
  startMonitoring(): Promise<boolean>;
  getUsageLog(): Promise<IosUsageLog>;

  // Android
  openUsageAccessSettings(): Promise<boolean>;
  getInstalledApps(): Promise<InstalledApp[]>;
  getAppIcon(packageName: string): Promise<string | null>;
  setSelectedPackages(packages: string[] | null): Promise<NativeAndroidSelection>;
  getDailyUsage(days: number): Promise<NativeDailyUsage[]>;
  getAppUsage(dayOffset: number): Promise<NativeAppUsageBreakdown>;
}

export default requireOptionalNativeModule<CattusScreenTimeNativeModule>('CattusScreenTime');
