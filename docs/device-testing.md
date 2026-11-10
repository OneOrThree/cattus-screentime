# Testing on a device

Unit tests cover the rules: the session rebuild on Android and the log-to-days computation on iOS. They cannot cover what the operating system does. Run these checks on a real device with the example app before you rely on a release. Write down the device, the OS version and the date with each result.

## Set up

```sh
cd example
npm install
npx expo run:ios --device       # needs a signing team with Family Controls and App Groups
npx expo run:android --device
```

For iOS, set `ios.appleTeamId` in `example/app.json` or choose the team in Xcode, and change the bundle identifiers and the App Group to ones your team owns. The App Group must be the same in `app.json` and in `targets/screentime-monitor/Info.plist`.

## Checks

Use the selected apps for a known time with a stopwatch. iOS reports a lower bound in 15-minute steps, so use an app for at least 20 minutes to see the first step.

| # | Step | Expected |
| --- | --- | --- |
| 1 | Fresh install, open the app. | Status `notDetermined`. Usage section is empty. No crash. |
| 2 | Request permission and allow it. | Status `approved`. Android: the promise resolves when you come back from Settings. |
| 3 | Request permission and deny it (reinstall first). | iOS `denied`. Android `denied` after you return from Settings without turning it on. |
| 4 | iOS: choose one app. Use it for 20 minutes. Refresh. | Today shows `≥ 15 min (partial)`, because monitoring started today. |
| 5 | Android: save one app. Use it for 10 minutes. Refresh. | Today shows about 10 minutes more than before. The per-app list shows the app. |
| 6 | Leave monitoring running past midnight, use the app 20 minutes on each side of midnight. | Each day shows its own usage. The new day is `full`. |
| 7 | Turn the screen off for 30 minutes with the app open. | The 30 minutes are not counted. |
| 8 | Restart the device, then open the app. | Past values are unchanged. iOS: `startMonitoring` returns `true` and keeps counting. |
| 9 | Turn the permission off in Settings, return to the app. | `getDailyUsage` rejects `ERR_NOT_AUTHORIZED`. The app shows no minutes. |
| 10 | Turn the permission on again and return. | iOS: monitoring registers again and today is `partial`. Android: values come back. |
| 11 | iOS: change the selection during the day. | Today is `partial`. The next day is `full`. |
| 12 | Delete all stored data (Reset). | Selection is cleared. iOS: all days are `none`. |
| 13 | Android: split screen with two selected apps for 10 minutes. | Both apps get 10 minutes. |

## Report

Record each check as pass or fail with the observed value. A failure in 6, 8 or 10 usually means an operating system behavior that the rules in [architecture.md](architecture.md) do not handle yet; open an issue with the values from `getIosUsageLog()` (iOS) or the per-app breakdown (Android).
