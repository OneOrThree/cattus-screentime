import ExpoModulesCore

// Expo derives an error code from the class name (`FooBarError` becomes `ERR_FOO_BAR`), but only
// when the name ends in `Error` and matches the contract. Every class here overrides `code`
// instead, so the JavaScript `error.code` is exactly one of the codes in
// `CattusScreenTimeErrorCode`, whatever the class is called.

final class NotAuthorizedException: Exception, @unchecked Sendable {
  override var code: String { "ERR_NOT_AUTHORIZED" }
  override var reason: String { "Screen Time access is not approved. Call requestAuthorization() first." }
}

final class NoSelectionException: Exception, @unchecked Sendable {
  override var code: String { "ERR_NO_SELECTION" }
  override var reason: String { "No apps are selected. Call presentAppPicker() first." }
}

final class AppGroupMissingException: Exception, @unchecked Sendable {
  override var code: String { "ERR_APP_GROUP_MISSING" }
  override var reason: String {
    "The Info.plist key CattusScreenTimeAppGroup is missing or empty, or the App Group is not available."
  }
}

final class MonitoringFailedException: Exception, @unchecked Sendable {
  override var code: String { "ERR_MONITORING_FAILED" }
  override var reason: String { "DeviceActivityCenter could not start monitoring." }
}

final class NoViewControllerException: Exception, @unchecked Sendable {
  override var code: String { "ERR_NO_VIEW_CONTROLLER" }
  override var reason: String { "There is no view controller to present the app picker from." }
}
