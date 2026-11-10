# cattus-screentime

Measure how long a user spends in the apps they choose, on iOS and Android, from React Native.

- **iOS:** Family Controls permission, the system app picker, and DeviceActivity thresholds recorded by a monitor extension.
- **Android:** Usage Access permission, an installed-app list for your own picker, and usage rebuilt from the raw `UsageStatsManager` event log.

The code was extracted from [Cattus](https://github.com/OneOrThree), a focus app, where it measures screen time in production. This package keeps only the measurement. It does not block apps.

> Version 0.1.0. Built and unit-tested on Expo SDK 57 and React Native 0.86. See [Status](#status) for what has been verified.

## What you get

| | iOS | Android |
| --- | --- | --- |
| Permission | Family Controls prompt | Opens Usage Access in Settings, resolves on return |
| Choose apps | System `FamilyActivityPicker` (your app never sees app names) | `getInstalledApps()` + `setSelectedPackages()`; you draw the list |
| Daily usage | Lower bound in 15-minute steps, days since monitoring started | Exact minutes, any day the system still keeps events for |
| Per-app usage | Not available to apps (Apple shows it only inside a DeviceActivityReport extension) | `getAppUsage()` |

Every day comes with a `coverage` value, so you can tell a measured 0 from a day that was not measured:

```ts
{ date: '2026-11-07', minutes: 45, granularityMinutes: 15, coverage: 'full' }
{ date: '2026-11-08', minutes: 15, granularityMinutes: 15, coverage: 'partial' } // monitoring started today
{ date: '2026-11-06', minutes: null, granularityMinutes: 15, coverage: 'none' }  // not measured
```

## Requirements

- An Expo app with a development build (`npx expo prebuild` or EAS Build). Expo Go cannot load the native code.
- Expo SDK 57, React Native 0.86. Other versions have not been tested.
- iOS 16.4 or later, and an Apple Developer account with the Family Controls capability (see [iOS setup](#ios-setup)).
- Android: no extra requirement beyond your app's `minSdkVersion`.

## Install

```sh
npm install github:OneOrThree/cattus-screentime
```

The package is not on npm. Installing from GitHub builds the TypeScript on install.

## iOS setup

iOS measures usage in a separate DeviceActivityMonitor extension that shares an App Group with your app. You need four things.

**1. Capabilities.** In the Apple Developer portal, enable **Family Controls** and **App Groups** for your app ID and for the extension's app ID (with apple-targets, `<your bundle id>.device-activity-monitor` unless you set `bundleIdentifier`), and create an App Group, for example `group.com.example.myapp`. Family Controls works for development builds once enabled. To ship to the App Store you must [request the distribution entitlement](https://developer.apple.com/contact/request/family-controls-distribution) from Apple for both app IDs.

**2. Config plugin.** Add the plugin with your App Group. It adds the Family Controls and App Group entitlements to the app and writes the App Group to `Info.plist` (`CattusScreenTimeAppGroup`), where the module reads it.

```json
{
  "expo": {
    "plugins": [
      ["cattus-screentime", { "appGroup": "group.com.example.myapp" }],
      "@bacons/apple-targets"
    ]
  }
}
```

**3. Extension target.** The recommended way is [`@bacons/apple-targets`](https://github.com/EvanBacon/expo-apple-targets) (`npm install -D @bacons/apple-targets`). Create `targets/screentime-monitor/` in your app with three files:

- `DeviceActivityMonitorExtension.swift`: copy it from `node_modules/cattus-screentime/ios-extension/`. Do not edit it; it only records threshold events.
- `Info.plist`: copy it from `node_modules/cattus-screentime/ios-extension/` and set `CattusScreenTimeAppGroup` to your App Group.
- `expo-target.config.js`:

  ```js
  module.exports = (config) => ({
    type: 'device-activity-monitor',
    deploymentTarget: '16.4',
    frameworks: ['DeviceActivity', 'FamilyControls'],
    entitlements: {
      'com.apple.developer.family-controls': true,
      'com.apple.security.application-groups':
        config.ios.entitlements['com.apple.security.application-groups'],
    },
  });
  ```

The [example app](example/targets/screentime-monitor) has these files. Without apple-targets, add a **Device Activity Monitor Extension** target in Xcode, replace its Swift file and Info.plist with the two files above, and give it the same two capabilities.

**4. Build.** Run `npx expo prebuild -p ios` and build to a device. The `.appex` must appear under `YourApp.app/PlugIns/`.

## Android setup

Nothing to configure. The library manifest declares `PACKAGE_USAGE_STATS` and a launcher-intent `<queries>` entry, and the manifest merger adds both to your app. It does not use `QUERY_ALL_PACKAGES`.

The user grants Usage Access in Settings. There is no system dialog, so explain why you need it before you call `requestAuthorization()`.

## Quick start

```tsx
import {
  getAuthorizationStatus,
  requestAuthorization,
  presentAppPicker,
  setSelectedPackages,
  startMonitoring,
  getDailyUsage,
  hasErrorCode,
} from 'cattus-screentime';
import { Platform } from 'react-native';

async function setUp() {
  if ((await getAuthorizationStatus()) !== 'approved') {
    const status = await requestAuthorization();
    if (status !== 'approved') return;
  }
  if (Platform.OS === 'ios') {
    await presentAppPicker(); // saves the selection and starts monitoring
  } else {
    await setSelectedPackages('all'); // or a list of package names
  }
}

// On every app start: re-registers monitoring if iOS dropped it, otherwise does nothing.
await startMonitoring();

try {
  const days = await getDailyUsage({ days: 7 }); // oldest first, today last
} catch (error) {
  if (hasErrorCode(error, 'ERR_NOT_AUTHORIZED')) {
    // The user turned the permission off. Ask again; do not show 0 minutes.
  }
}
```

The [example app](example/App.tsx) shows the whole flow on one screen.

## API

| Function | Platform | Returns |
| --- | --- | --- |
| `isSupported()` | both | `boolean` |
| `getAuthorizationStatus()` | both | `'approved' \| 'denied' \| 'notDetermined' \| 'unavailable'` |
| `requestAuthorization()` | both | status after the request |
| `openUsageAccessSettings()` | Android | `boolean`; opens Settings even when access is on |
| `presentAppPicker()` | iOS | selection counts, or `null` on Cancel |
| `startMonitoring()` | both | `boolean`; `false` on iOS when no apps were picked |
| `getSelection()` | both | `Selection \| null` |
| `getInstalledApps()` | Android | `{ packageName, label }[]` |
| `getAppIcon(packageName)` | Android | base64 PNG or `null` |
| `setSelectedPackages(packages \| 'all')` | Android | the saved selection |
| `getDailyUsage({ days })` | both | `DailyUsage[]`, `days` from 1 to 30 (default 7) |
| `getAppUsage({ dayOffset })` | Android | per-app usage for one day, `dayOffset` from -29 to 0 |
| `reset()` | both | deletes everything the module stored and stops monitoring |
| `getIosUsageLog()` | iOS | the raw threshold log, for debugging |
| `computeIosDailyUsage(log, { now, days })` | any | the pure function that turns the iOS log into days |

Calling a function on the wrong platform rejects with `ERR_UNSUPPORTED_PLATFORM`. Other error codes: `ERR_NOT_AUTHORIZED`, `ERR_APP_GROUP_MISSING`, `ERR_MONITORING_FAILED`, `ERR_NO_VIEW_CONTROLLER` (iOS), `ERR_USER_LOCKED`, `ERR_USAGE_UNAVAILABLE` (Android). Types are exported from the package.

## How the numbers are made, and their limits

**iOS.** Apple does not give apps usage numbers. The module registers one DeviceActivity event every 15 minutes of use, up to 900 minutes a day. The extension records each event with its time; `getDailyUsage` takes the largest bucket reached each day. So a value of 45 means at least 45 and less than 60 minutes. Usage before monitoring started is not counted: the day monitoring starts, or the selection changes, is `partial`, and earlier days are `none`. iOS sometimes delivers an event right after registration; events that arrive sooner than their bucket allows are dropped.

**Android.** The module pairs the raw foreground events of each app into sessions. A screen-off or shutdown closes open sessions, sessions across midnight are split by day, split-screen apps both count, and overlapping activities of one app count once. If the raw log is incomplete, for example because the device no longer keeps events that old, the module falls back to the system's daily totals and marks the day `partial`. A day with no app activity at all has no events either, so it is also `partial`, with 0 minutes.

The details, the iOS storage keys and the exact rules are in [docs/architecture.md](docs/architecture.md).

## Status

| Check | Result |
| --- | --- |
| JavaScript: type check, lint, Jest (day boundaries, daylight saving, coverage, permission errors) | Passing |
| Android: JVM unit tests of the session rebuild and selection history, debug build of the example | See CI |
| iOS: simulator build of the example with the extension embedded | Passing |
| Real devices | Not yet run for this package. See [docs/device-testing.md](docs/device-testing.md). |

## Development

```sh
npm install
npm test                 # Jest
npm run typecheck
npm run check:extension  # the example's extension copy matches ios-extension/
cd example && npm install && npx expo run:ios --device   # or run:android
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
