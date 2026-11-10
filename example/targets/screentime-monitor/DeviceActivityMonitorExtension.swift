import DeviceActivity
import Foundation

/// Records every usage threshold event in the App Group and does nothing else.
///
/// The extension stays thin on purpose. It runs with tight time and memory limits, the system
/// may start it at any moment, and it cannot reach the app's JavaScript. It does not register
/// monitoring again and it has no notion of days or selections. The app reads the raw `events`
/// list and interprets it in JavaScript, where the logic can be tested and changed without a
/// new extension release.
///
/// Keep this file identical to `example/targets/screentime-monitor/DeviceActivityMonitorExtension.swift`.
/// The key names and the activity and event names must match `CattusScreenTimeModule` in `ios/`.
final class DeviceActivityMonitorExtension: DeviceActivityMonitor {
  private let activityName = "cattus.screentime.usage"
  private let eventPrefix = "cattus.screentime.bucket."
  private let registeredAtKey = "cattus.screentime.registeredAt"
  private let eventsKey = "cattus.screentime.events"
  private let maxEvents = 1_000

  override func eventDidReachThreshold(
    _ event: DeviceActivityEvent.Name,
    activity: DeviceActivityName
  ) {
    super.eventDidReachThreshold(event, activity: activity)

    guard activity.rawValue == activityName,
      event.rawValue.hasPrefix(eventPrefix),
      let minutes = Int(event.rawValue.dropFirst(eventPrefix.count)),
      let group = Bundle.main.object(forInfoDictionaryKey: "CattusScreenTimeAppGroup") as? String,
      !group.isEmpty,
      let defaults = UserDefaults(suiteName: group)
    else { return }

    var events = defaults.array(forKey: eventsKey) as? [[String: Any]] ?? []
    events.append([
      "bucketMinutes": minutes,
      "firedAt": Date().timeIntervalSince1970,
      // The app writes this before it starts monitoring, so an event is always stamped with
      // the registration that produced it.
      "registeredAt": defaults.double(forKey: registeredAtKey),
    ])
    defaults.set(Array(events.suffix(maxEvents)), forKey: eventsKey)
  }
}
