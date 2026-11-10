import type { Coverage, DailyUsage, IosUsageLog } from './CattusScreenTime.types';

/**
 * iOS sometimes delivers threshold events right after monitoring is registered, before the
 * apps were used for that long. An event whose bucket is larger than the minutes elapsed
 * since counting started, plus this slack, is dropped.
 */
const PLAUSIBILITY_SLACK_MINUTES = 5;

const MS_PER_MINUTE = 60_000;

type DayRange = {
  date: string;
  /** Local midnight that starts the day (ms). */
  start: number;
  /** Next local midnight (ms). */
  nextStart: number;
  /** `nextStart`, or now for today (ms). */
  end: number;
  isToday: boolean;
};

/** Local calendar date of `date` as `YYYY-MM-DD`. */
export function localDateKey(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * The last `days` local days, oldest first, today last. Days are built from calendar
 * fields, not by subtracting 24 hours, so a 23- or 25-hour day around a daylight saving
 * change keeps its real boundaries.
 */
function dayRanges(now: number, days: number): DayRange[] {
  const today = new Date(now);
  const ranges: DayRange[] = [];
  for (let offset = days - 1; offset >= 0; offset--) {
    const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset);
    const next = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset + 1);
    ranges.push({
      date: localDateKey(start),
      start: start.getTime(),
      nextStart: next.getTime(),
      end: Math.min(next.getTime(), now),
      isToday: offset === 0,
    });
  }
  return ranges;
}

function coverageOf(day: DayRange, registrations: number[], monitoringActive: boolean): Coverage {
  if (!registrations.some((at) => at < day.end)) return 'none';
  // Usage before a registration made during the day, or with the previous selection, is
  // not in the count.
  if (registrations.some((at) => at > day.start && at < day.end)) return 'partial';
  if (day.isToday && !monitoringActive) return 'partial';
  return 'full';
}

/**
 * Turns the raw iOS log into one `DailyUsage` per local day.
 *
 * Each registration counts from zero, and from local midnight again on each new day of the
 * repeating schedule. So the minutes of a day are the sum, over the registrations that
 * produced events that day, of the largest plausible bucket each one reached.
 *
 * @param log What `getUsageLog()` returned.
 * @param options.now Current time in milliseconds since the epoch.
 * @param options.days Number of days to return, today included.
 */
export function computeIosDailyUsage(
  log: IosUsageLog,
  options: { now: number; days: number }
): DailyUsage[] {
  const registrations = log.registrations.map((r) => r.registeredAt * 1000).sort((a, b) => a - b);

  return dayRanges(options.now, options.days).map((day) => {
    const coverage = coverageOf(day, registrations, log.monitoringActive);
    if (coverage === 'none') {
      return { date: day.date, minutes: null, granularityMinutes: log.bucketMinutes, coverage };
    }

    const largestBucketByRegistration = new Map<number, number>();
    for (const event of log.events) {
      const firedAt = event.firedAt * 1000;
      if (firedAt < day.start || firedAt >= day.nextStart) continue;
      if (!(event.bucketMinutes > 0)) continue;

      const countingFrom = Math.max(day.start, event.registeredAt * 1000);
      const elapsedMinutes = (firedAt - countingFrom) / MS_PER_MINUTE;
      if (event.bucketMinutes > elapsedMinutes + PLAUSIBILITY_SLACK_MINUTES) continue;

      const previous = largestBucketByRegistration.get(event.registeredAt) ?? 0;
      largestBucketByRegistration.set(event.registeredAt, Math.max(previous, event.bucketMinutes));
    }

    let minutes = 0;
    for (const bucket of largestBucketByRegistration.values()) minutes += bucket;
    return {
      date: day.date,
      minutes: Math.min(minutes, log.maxMinutes),
      granularityMinutes: log.bucketMinutes,
      coverage,
    };
  });
}
