import type { IosUsageLog } from '../CattusScreenTime.types';
import { computeIosDailyUsage, localDateKey } from '../iosUsage';

// Tests build times from local calendar fields, so they hold in any time zone. Jest runs
// in America/New_York (see jest.globalSetup.js) so that the daylight saving test is real.
const at = (month: number, day: number, hour = 0, minute = 0, second = 0) =>
  new Date(2026, month - 1, day, hour, minute, second).getTime();
const sec = (ms: number) => ms / 1000;

function log(partial: Partial<IosUsageLog> = {}): IosUsageLog {
  return {
    bucketMinutes: 15,
    maxMinutes: 900,
    monitoringActive: true,
    registrations: [],
    events: [],
    ...partial,
  };
}

const registration = (ms: number) => ({ registeredAt: sec(ms), signature: 'sig' });
const event = (bucketMinutes: number, firedAtMs: number, registeredAtMs: number) => ({
  bucketMinutes,
  firedAt: sec(firedAtMs),
  registeredAt: sec(registeredAtMs),
});

describe('computeIosDailyUsage', () => {
  const registeredLongAgo = at(10, 1, 9);

  it('returns the requested days, oldest first and today last', () => {
    const days = computeIosDailyUsage(log({ registrations: [registration(registeredLongAgo)] }), {
      now: at(10, 20, 12),
      days: 3,
    });
    expect(days.map((d) => d.date)).toEqual(['2026-10-18', '2026-10-19', '2026-10-20']);
  });

  it('takes the largest plausible bucket of a fully monitored day', () => {
    const reg = registeredLongAgo;
    const days = computeIosDailyUsage(
      log({
        registrations: [registration(reg)],
        events: [
          event(15, at(10, 19, 8, 20), reg),
          event(30, at(10, 19, 9, 0), reg),
          event(45, at(10, 19, 21, 0), reg),
        ],
      }),
      { now: at(10, 20, 12), days: 2 }
    );
    expect(days[0]).toEqual({
      date: '2026-10-19',
      minutes: 45,
      granularityMinutes: 15,
      coverage: 'full',
    });
    expect(days[1]).toEqual({
      date: '2026-10-20',
      minutes: 0,
      granularityMinutes: 15,
      coverage: 'full',
    });
  });

  it('reports a day before the first registration as not measured, never as 0', () => {
    const reg = at(10, 20, 10);
    const [yesterday] = computeIosDailyUsage(log({ registrations: [registration(reg)] }), {
      now: at(10, 20, 12),
      days: 2,
    });
    expect(yesterday).toEqual({
      date: '2026-10-19',
      minutes: null,
      granularityMinutes: 15,
      coverage: 'none',
    });
  });

  it('reports no days as measured when nothing was ever registered', () => {
    const days = computeIosDailyUsage(log(), { now: at(10, 20, 12), days: 7 });
    expect(days.every((d) => d.coverage === 'none' && d.minutes === null)).toBe(true);
  });

  it('marks the day of a mid-day registration as partial and counts from the registration', () => {
    const reg = at(10, 20, 10);
    const [today] = computeIosDailyUsage(
      log({ registrations: [registration(reg)], events: [event(15, at(10, 20, 10, 30), reg)] }),
      { now: at(10, 20, 12), days: 1 }
    );
    expect(today).toMatchObject({ minutes: 15, coverage: 'partial' });
  });

  it('adds up the registrations of a day, because each one counts from zero', () => {
    const first = registeredLongAgo;
    const second = at(10, 19, 14);
    const [day] = computeIosDailyUsage(
      log({
        registrations: [registration(first), registration(second)],
        events: [
          event(15, at(10, 19, 9), first),
          event(30, at(10, 19, 11), first),
          event(15, at(10, 19, 15), second),
        ],
      }),
      { now: at(10, 20, 12), days: 2 }
    );
    expect(day).toMatchObject({ date: '2026-10-19', minutes: 45, coverage: 'partial' });
  });

  it('drops an event that fires sooner after registration than its bucket allows', () => {
    const reg = at(10, 20, 10);
    const [today] = computeIosDailyUsage(
      log({
        registrations: [registration(reg)],
        // 60 minutes reported one minute after registering: delivered early, not real.
        events: [event(60, at(10, 20, 10, 1), reg), event(15, at(10, 20, 10, 40), reg)],
      }),
      { now: at(10, 20, 12), days: 1 }
    );
    expect(today?.minutes).toBe(15);
  });

  it('drops an event larger than the time elapsed since midnight', () => {
    const reg = registeredLongAgo;
    const [today] = computeIosDailyUsage(
      log({ registrations: [registration(reg)], events: [event(120, at(10, 20, 0, 30), reg)] }),
      { now: at(10, 20, 12), days: 1 }
    );
    expect(today?.minutes).toBe(0);
  });

  it('keeps an event within the five-minute slack', () => {
    const reg = registeredLongAgo;
    const [today] = computeIosDailyUsage(
      log({ registrations: [registration(reg)], events: [event(15, at(10, 20, 0, 11), reg)] }),
      { now: at(10, 20, 12), days: 1 }
    );
    expect(today?.minutes).toBe(15);
  });

  it('puts events on each side of midnight on their own day', () => {
    const reg = registeredLongAgo;
    const days = computeIosDailyUsage(
      log({
        registrations: [registration(reg)],
        events: [event(30, at(10, 19, 23, 59, 59), reg), event(15, at(10, 20, 0, 20), reg)],
      }),
      { now: at(10, 20, 12), days: 2 }
    );
    expect(days.map((d) => d.minutes)).toEqual([30, 15]);
  });

  it('marks today as partial when iOS no longer lists the monitoring activity', () => {
    const reg = registeredLongAgo;
    const days = computeIosDailyUsage(
      log({
        monitoringActive: false,
        registrations: [registration(reg)],
        events: [event(15, at(10, 20, 9), reg)],
      }),
      { now: at(10, 20, 12), days: 2 }
    );
    expect(days.map((d) => d.coverage)).toEqual(['full', 'partial']);
    expect(days[1]?.minutes).toBe(15);
  });

  it('caps a day at maxMinutes', () => {
    const first = registeredLongAgo;
    const second = at(10, 19, 16);
    const [day] = computeIosDailyUsage(
      log({
        maxMinutes: 60,
        registrations: [registration(first), registration(second)],
        events: [event(45, at(10, 19, 12), first), event(45, at(10, 19, 20), second)],
      }),
      { now: at(10, 20, 12), days: 2 }
    );
    expect(day?.minutes).toBe(60);
  });

  it('counts an event without a registration time from midnight', () => {
    const reg = registeredLongAgo;
    const days = computeIosDailyUsage(
      log({
        registrations: [registration(reg)],
        events: [{ bucketMinutes: 15, firedAt: sec(at(10, 20, 9)), registeredAt: 0 }],
      }),
      { now: at(10, 20, 12), days: 1 }
    );
    expect(days[0]?.minutes).toBe(15);
  });

  it('keeps the real boundaries of a 25-hour day at the end of daylight saving time', () => {
    // In America/New_York, 2026-11-01 is 25 hours long. Subtracting 24 hours from the next
    // midnight would start that day at 01:00 and move the 00:30 event to October 31.
    const reg = registeredLongAgo;
    const days = computeIosDailyUsage(
      log({ registrations: [registration(reg)], events: [event(15, at(11, 1, 0, 30), reg)] }),
      { now: at(11, 2, 12), days: 3 }
    );
    expect(days.map((d) => [d.date, d.minutes])).toEqual([
      ['2026-10-31', 0],
      ['2026-11-01', 15],
      ['2026-11-02', 0],
    ]);
  });
});

describe('localDateKey', () => {
  it('formats the local calendar date with zero padding', () => {
    expect(localDateKey(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
  });
});
