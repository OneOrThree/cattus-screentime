import CryptoKit
import DeviceActivity
import FamilyControls
import Foundation

/// Names shared with the DeviceActivityMonitor extension (see `ios-extension/`). Keep both in sync.
enum CattusKeys {
  static let prefix = "cattus.screentime."
  static let selection = prefix + "selection"
  static let registeredAt = prefix + "registeredAt"
  static let registeredSignature = prefix + "registeredSignature"
  static let registrations = prefix + "registrations"
  static let events = prefix + "events"

  static let activityName = prefix + "usage"
  static let eventPrefix = prefix + "bucket."
  static let appGroupInfoKey = "CattusScreenTimeAppGroup"

  static let bucketMinutes = 15
  static let maxMinutes = 900
  static let maxRegistrations = 50
}

/// Reads and writes the App Group storage and registers the usage thresholds.
struct CattusStorage {
  let defaults: UserDefaults

  /// Throws `AppGroupMissingException` when the Info.plist key is missing or the group cannot be opened.
  init() throws {
    guard let group = Bundle.main.object(forInfoDictionaryKey: CattusKeys.appGroupInfoKey) as? String,
      !group.trimmingCharacters(in: .whitespaces).isEmpty,
      let defaults = UserDefaults(suiteName: group)
    else {
      throw AppGroupMissingException()
    }
    self.defaults = defaults
  }

  // MARK: Selection

  var selection: FamilyActivitySelection? {
    defaults.data(forKey: CattusKeys.selection).flatMap {
      try? JSONDecoder().decode(FamilyActivitySelection.self, from: $0)
    }
  }

  static func isEmpty(_ selection: FamilyActivitySelection) -> Bool {
    selection.applicationTokens.isEmpty && selection.categoryTokens.isEmpty
      && selection.webDomainTokens.isEmpty
  }

  static func counts(_ selection: FamilyActivitySelection) -> [String: Int] {
    [
      "applications": selection.applicationTokens.count,
      "categories": selection.categoryTokens.count,
      "webDomains": selection.webDomainTokens.count,
    ]
  }

  /// SHA-256 of the sorted, encoded tokens. The same selection always gives the same value,
  /// so the module can tell whether a registration already covers the saved selection.
  static func signature(_ selection: FamilyActivitySelection) -> String {
    let canonical = [
      "applications:\(canonicalTokens(selection.applicationTokens).joined(separator: ","))",
      "categories:\(canonicalTokens(selection.categoryTokens).joined(separator: ","))",
      "webDomains:\(canonicalTokens(selection.webDomainTokens).joined(separator: ","))",
    ].joined(separator: "|")
    return SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  private static func canonicalTokens<S: Sequence>(_ tokens: S) -> [String] where S.Element: Encodable {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    return tokens.compactMap { try? encoder.encode($0).base64EncodedString() }.sorted()
  }

  // MARK: Monitoring

  static let activity = DeviceActivityName(CattusKeys.activityName)

  /// Every day from 00:00 to 23:59, in the device's current time zone (no `timeZone` is set).
  private static var schedule: DeviceActivitySchedule {
    DeviceActivitySchedule(
      intervalStart: DateComponents(hour: 0, minute: 0),
      intervalEnd: DateComponents(hour: 23, minute: 59),
      repeats: true
    )
  }

  /// One event per 15 minutes up to 900 minutes.
  ///
  /// Web domains are sent only when the selection has no categories.
  private static func events(_ selection: FamilyActivitySelection)
    -> [DeviceActivityEvent.Name: DeviceActivityEvent]
  {
    let webDomains = selection.categoryTokens.isEmpty ? selection.webDomainTokens : []
    var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
    for minute in stride(from: CattusKeys.bucketMinutes, through: CattusKeys.maxMinutes, by: CattusKeys.bucketMinutes) {
      events[DeviceActivityEvent.Name(CattusKeys.eventPrefix + "\(minute)")] = DeviceActivityEvent(
        applications: selection.applicationTokens,
        categories: selection.categoryTokens,
        webDomains: webDomains,
        threshold: DateComponents(hour: minute / 60, minute: minute % 60)
      )
    }
    return events
  }

  var isMonitoringActive: Bool {
    DeviceActivityCenter().activities.contains(Self.activity)
  }

  var registeredSignature: String? { defaults.string(forKey: CattusKeys.registeredSignature) }

  /// Registers the thresholds for `selection`.
  ///
  /// `registeredAt` and `registeredSignature` are written before `startMonitoring`: an event can
  /// fire as soon as monitoring starts, and the extension stamps each event with the
  /// `registeredAt` it reads at that moment. Written afterwards, an early event would carry the
  /// previous registration and be counted against the wrong selection.
  ///
  /// On failure the previous values are restored. If `previousSelection` is given, the previous
  /// monitoring is restarted so that a failed change does not leave the user unmonitored.
  func register(_ selection: FamilyActivitySelection, previousSelection: FamilyActivitySelection?) throws {
    let center = DeviceActivityCenter()
    let previousRegisteredAt = defaults.object(forKey: CattusKeys.registeredAt)
    let previousSignature = defaults.object(forKey: CattusKeys.registeredSignature)

    func begin(_ selection: FamilyActivitySelection) throws {
      let now = Date().timeIntervalSince1970
      let signature = Self.signature(selection)
      defaults.set(now, forKey: CattusKeys.registeredAt)
      defaults.set(signature, forKey: CattusKeys.registeredSignature)
      center.stopMonitoring([Self.activity])
      try center.startMonitoring(Self.activity, during: Self.schedule, events: Self.events(selection))
      appendRegistration(registeredAt: now, signature: signature)
    }

    do {
      try begin(selection)
    } catch {
      var restarted = false
      if let previousSelection, !Self.isEmpty(previousSelection) {
        // The restart is a new registration: its counters start from zero at a new time, so it
        // keeps its own `registeredAt` and is logged like any other registration.
        restarted = (try? begin(previousSelection)) != nil
      }
      if !restarted {
        restore(previousRegisteredAt, forKey: CattusKeys.registeredAt)
        restore(previousSignature, forKey: CattusKeys.registeredSignature)
      }
      throw MonitoringFailedException().causedBy(error)
    }
  }

  private func restore(_ value: Any?, forKey key: String) {
    if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
  }

  private func appendRegistration(registeredAt: Double, signature: String) {
    var list = defaults.array(forKey: CattusKeys.registrations) as? [[String: Any]] ?? []
    list.append(["registeredAt": registeredAt, "signature": signature])
    defaults.set(Array(list.suffix(CattusKeys.maxRegistrations)), forKey: CattusKeys.registrations)
  }

  // MARK: Log

  func usageLog() -> [String: Any] {
    func number(_ value: Any?) -> Double? { (value as? NSNumber)?.doubleValue }

    let registrations = (defaults.array(forKey: CattusKeys.registrations) as? [[String: Any]] ?? [])
      .compactMap { item -> [String: Any]? in
        guard let at = number(item["registeredAt"]), let signature = item["signature"] as? String else { return nil }
        return ["registeredAt": at, "signature": signature]
      }
      .sorted { ($0["registeredAt"] as? Double ?? 0) < ($1["registeredAt"] as? Double ?? 0) }

    let events = (defaults.array(forKey: CattusKeys.events) as? [[String: Any]] ?? [])
      .compactMap { item -> [String: Any]? in
        guard let minutes = number(item["bucketMinutes"]), let firedAt = number(item["firedAt"]) else { return nil }
        return [
          "bucketMinutes": Int(minutes),
          "firedAt": firedAt,
          "registeredAt": number(item["registeredAt"]) ?? 0,
        ]
      }

    return [
      "bucketMinutes": CattusKeys.bucketMinutes,
      "maxMinutes": CattusKeys.maxMinutes,
      "monitoringActive": isMonitoringActive,
      "registrations": registrations,
      "events": events,
    ]
  }

  /// Stops monitoring and deletes every key this module owns.
  func reset() {
    DeviceActivityCenter().stopMonitoring([Self.activity])
    for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(CattusKeys.prefix) {
      defaults.removeObject(forKey: key)
    }
  }
}
