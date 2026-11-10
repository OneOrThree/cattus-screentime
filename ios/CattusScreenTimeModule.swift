import DeviceActivity
import ExpoModulesCore
import FamilyControls
import SwiftUI
import UIKit

public class CattusScreenTimeModule: Module {
  public func definition() -> ModuleDefinition {
    Name("CattusScreenTime")

    AsyncFunction("getAuthorizationStatus") { () -> String in
      Self.statusString()
    }

    AsyncFunction("requestAuthorization") { () -> String in
      do {
        try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
      } catch {
        // A cancel or a denial is not an error for the caller: it reads the status that results.
      }
      return Self.statusString()
    }

    AsyncFunction("getSelection") { () -> [String: Int]? in
      let storage = try CattusStorage()
      return storage.selection.map(CattusStorage.counts)
    }

    AsyncFunction("presentAppPicker") { (promise: Promise) in
      do {
        let storage = try CattusStorage()
        try Self.requireApproved()
        // `runOnQueue(.main)` runs this closure on the main thread.
        try MainActor.assumeIsolated { try Self.presentPicker(storage: storage, promise: promise) }
      } catch {
        promise.reject(error)
      }
    }
    .runOnQueue(.main)

    AsyncFunction("startMonitoring") { () -> Bool in
      let storage = try CattusStorage()
      try Self.requireApproved()
      guard let selection = storage.selection, !CattusStorage.isEmpty(selection) else { return false }
      if storage.isMonitoringActive,
        storage.registeredSignature == CattusStorage.signature(selection)
      {
        return true
      }
      try storage.register(selection, previousSelection: nil)
      return true
    }

    AsyncFunction("getUsageLog") { () -> [String: Any] in
      try CattusStorage().usageLog()
    }

    AsyncFunction("reset") { () -> Void in
      try CattusStorage().reset()
    }
  }

  // MARK: Authorization

  private static func statusString() -> String {
    switch AuthorizationCenter.shared.authorizationStatus {
    case .approved, .approvedWithDataAccess: return "approved"
    case .denied: return "denied"
    case .notDetermined: return "notDetermined"
    @unknown default: return "notDetermined"
    }
  }

  private static func requireApproved() throws {
    guard statusString() == "approved" else {
      throw NotAuthorizedException()
    }
  }

  // MARK: Picker

  @MainActor
  private static func presentPicker(storage: CattusStorage, promise: Promise) throws {
    guard let top = topViewController() else { throw NoViewControllerException() }
    let previousData = storage.defaults.data(forKey: CattusKeys.selection)
    let previous = storage.selection

    // Cancel and Done both finish once. Done saves and registers only after the sheet is
    // dismissed, so the registration never runs under a presented picker.
    var finished = false
    func finish(_ choice: FamilyActivitySelection?) {
      guard !finished else { return }
      finished = true
      top.dismiss(animated: true) {
        guard let choice else {
          promise.resolve(nil)
          return
        }
        do {
          guard let data = try? JSONEncoder().encode(choice) else { throw MonitoringFailedException() }
          storage.defaults.set(data, forKey: CattusKeys.selection)
          do {
            try storage.register(choice, previousSelection: previous)
          } catch {
            if let previousData {
              storage.defaults.set(previousData, forKey: CattusKeys.selection)
            } else {
              storage.defaults.removeObject(forKey: CattusKeys.selection)
            }
            throw error
          }
          promise.resolve(CattusStorage.counts(choice))
        } catch {
          promise.reject(error)
        }
      }
    }

    let picker = CattusActivityPicker(
      initialSelection: previous ?? FamilyActivitySelection(),
      onDone: { finish($0) },
      onCancel: { finish(nil) }
    )
    let host = UIHostingController(rootView: picker)
    host.isModalInPresentation = true
    top.present(host, animated: true)
  }

  @MainActor
  private static func topViewController() -> UIViewController? {
    let window = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows)
      .first(where: \.isKeyWindow)
    var top = window?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    return top
  }
}
