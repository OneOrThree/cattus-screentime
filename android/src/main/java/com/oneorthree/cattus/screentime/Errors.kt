package com.oneorthree.cattus.screentime

import expo.modules.kotlin.exception.CodedException

// The code is passed explicitly so that JavaScript sees exactly these strings.
// Expo would otherwise infer a code from the class name.

/** Usage Access is off. */
internal class NotAuthorizedException :
  CodedException("ERR_NOT_AUTHORIZED", "Usage Access is not granted.", null)

/** The device has not been unlocked since boot, so usage data is not readable yet. */
internal class UserLockedException :
  CodedException("ERR_USER_LOCKED", "The user has not unlocked the device since boot.", null)

/** The system could not return usage data. */
internal class UsageUnavailableCodedException(cause: Throwable) :
  CodedException("ERR_USAGE_UNAVAILABLE", "Usage data is unavailable: ${cause.message}", cause)

/** A function argument is outside its allowed range. */
internal class InvalidArgumentException(message: String) :
  CodedException("ERR_INVALID_ARGUMENT", message, null)
