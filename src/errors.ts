import type { CattusScreenTimeErrorCode } from './CattusScreenTime.types';

/**
 * Error thrown by the JavaScript layer. Errors from the native module are Expo coded errors
 * with the same `code` values, so callers can check `error.code` in both cases.
 */
export class CattusScreenTimeError extends Error {
  readonly code: CattusScreenTimeErrorCode;

  constructor(code: CattusScreenTimeErrorCode, message: string) {
    super(message);
    this.name = 'CattusScreenTimeError';
    this.code = code;
  }
}

/** Whether `error` carries the given code, from this layer or from native code. */
export function hasErrorCode(error: unknown, code: CattusScreenTimeErrorCode): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    (error as { code?: unknown }).code === code
  );
}
