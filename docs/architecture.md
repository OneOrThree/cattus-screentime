# Architecture

This document is the contract between the JavaScript layer (`src/`), the iOS module (`ios/`), the iOS DeviceActivityMonitor extension (`ios-extension/`) and the Android module (`android/`). Change it together with the code.

## Why the platforms differ

| | iOS | Android |
| --- | --- | --- |
| Permission | Family Controls, `.individual`. System prompt. | Usage Access (`PACKAGE_USAGE_STATS`). No prompt: the user turns it on in Settings. |
| Choosing apps | `FamilyActivityPicker`. The app receives opaque tokens and never sees app names. | The app reads the launchable apps and their names, and draws its own list. |
| Reading usage | The app cannot read usage numbers. A DeviceActivityMonitor extension receives an event each time the selected apps reach a threshold. This module registers thresholds every 15 minutes up to 900 minutes, so the result is a lower bound in 15-minute steps. | `UsageStatsManager.queryEvents` returns the raw foreground events. The module rebuilds sessions from them and reports exact minutes. |
| Past days | Only days that were monitored. | Any day that the system still keeps raw events for (a few days, depends on the device). Older days fall back to `queryUsageStats(INTERVAL_DAILY)` and are marked `partial`. |

## Native module `CattusScreenTime`

All functions are `AsyncFunction`s. Errors are coded exceptions; the codes are `CattusScreenTimeErrorCode` in `src/CattusScreenTime.types.ts`.

### Both platforms

| Function | Returns | Notes |
| --- | --- | --- |
| `getAuthorizationStatus()` | `'approved' \| 'denied' \| 'notDetermined'` | |
| `requestAuthorization()` | status after the request | iOS: system prompt; a cancel or an error from the prompt resolves the resulting status. Android: opens Usage Access settings and resolves when the app returns to the foreground. If Usage Access is already on, resolves at once. |
| `getSelection()` | iOS `{ applications, categories, webDomains }` or `null`; Android `{ allApps, packages }` | Android with no saved selection returns `{ allApps: true, packages: [] }`. |
| `reset()` | `void` | Stops monitoring (iOS) and deletes everything this module stored. |

### iOS only

| Function | Returns | Notes |
| --- | --- | --- |
| `presentAppPicker()` | selection counts or `null` on cancel | Presents a FamilyActivityPicker sheet with Cancel and Done. Done with an empty selection is disabled. On Done: saves the selection, then registers monitoring (see below). Rejects `ERR_MONITORING_FAILED` if registration fails; the saved selection is then rolled back. |
| `startMonitoring()` | `true` if monitoring is active after the call, `false` if there is no saved selection | Idempotent. If the activity is already registered with the same selection signature, it does nothing and adds no registration. Rejects `ERR_NOT_AUTHORIZED` when not approved. |
| `getUsageLog()` | `IosUsageLog` | Raw log; JavaScript computes the days. |

### Android only

| Function | Returns | Notes |
| --- | --- | --- |
| `openUsageAccessSettings()` | `boolean` | Always opens the Usage Access list, even when access is on (so the user can turn it off). `false` if no activity can handle the intent. |
| `getInstalledApps()` | `{ packageName, label }[]` | Launchable apps, this app excluded, sorted by label. |
| `getAppIcon(packageName)` | base64 PNG (96×96, no data-URI prefix) or `null` | |
| `setSelectedPackages(packages \| null)` | `{ allApps, packages }` | `null` or `[]` means all apps. |
| `getDailyUsage(days)` | `{ date, minutes, coverage }[]` | `days` from 1 to 30, oldest first, today last. Rejects `ERR_NOT_AUTHORIZED` if Usage Access is off before or after the query, and `ERR_USER_LOCKED` before the first unlock after boot. |
| `getAppUsage(dayOffset)` | `{ date, totalSeconds, otherSeconds, apps, coverage }` | `dayOffset` 0 = today, -1 = yesterday, down to -29. Same errors as `getDailyUsage`. |

Android days are local calendar days of the device time zone. A past day is measured with the selection that was in effect on that day: when the selection changes, the module keeps the previous selection for the past days of the change date.

## iOS storage

The app and the extension share an App Group. Its identifier is read from the `CattusScreenTimeAppGroup` key of each target's `Info.plist`. If the key is missing, functions that need storage reject `ERR_APP_GROUP_MISSING`.

All keys live in `UserDefaults(suiteName: <app group>)` and start with `cattus.screentime.`.

| Key | Type | Writer | Meaning |
| --- | --- | --- | --- |
| `cattus.screentime.selection` | `Data` (JSON `FamilyActivitySelection`) | app | Selected apps. |
| `cattus.screentime.registeredAt` | `Double` (epoch s) | app | When the current monitoring registration was made. |
| `cattus.screentime.registeredSignature` | `String` | app | SHA-256 of the sorted selection tokens of the current registration. |
| `cattus.screentime.registrations` | `[[String: Any]]` `{ registeredAt: Double, signature: String }` | app | Every successful registration, oldest first, last 50 kept. |
| `cattus.screentime.events` | `[[String: Any]]` `{ bucketMinutes: Int, firedAt: Double, registeredAt: Double }` | extension | Every threshold event, oldest first, last 1,000 kept. `registeredAt` is the value of `cattus.screentime.registeredAt` when the event fired. |

Activity name: `cattus.screentime.usage`. Event names: `cattus.screentime.bucket.<minutes>` for minutes 15, 30, …, 900. Schedule: 00:00 to 23:59 local time, repeating daily. Each event has the selection's applications and categories; web domains are included only when the selection has no categories.

### Registration order

1. Write `registeredAt` (now) and `registeredSignature` first, because an event can fire as soon as monitoring starts.
2. `stopMonitoring` the activity, then `startMonitoring` it with the new events.
3. On success, append to `registrations`.
4. On failure, if there was a previous selection, register it again with steps 1–3. That restart is a new registration with its own `registeredAt`, because its counters start from zero. If there is no previous selection or the restart fails too, restore the previous `registeredAt` and `registeredSignature`. Either way, reject `ERR_MONITORING_FAILED`.

### The extension

The extension only appends to `events`. It does no computation and never registers monitoring. All interpretation is in `src/iosUsage.ts`, so it runs in Jest.

## From the iOS log to days (`computeIosDailyUsage`)

For each local day `[start, end)`, where `end` is the next midnight or now for today:

1. **Coverage.** Let `R` be the registrations sorted by time.
   - No registration at or before `end` → `none`, `minutes: null`.
   - A registration inside `(start, end)` → `partial`. Usage before it, or with a different selection, is not in the count.
   - Otherwise → `full`. For today, `partial` instead if `monitoringActive` is false.
2. **Plausible events.** Keep events with `firedAt` in `[start, end)` whose bucket is not larger than the minutes elapsed since `max(start, registeredAt)` plus 5 minutes of slack. iOS sometimes delivers threshold events right after registration; this drops them.
3. **Minutes.** Group the plausible events by `registeredAt`. Each registration counts from zero, so the day total is the sum of the largest bucket of each group, capped at `maxMinutes`. A `full` or `partial` day with no plausible events has `0` minutes.
