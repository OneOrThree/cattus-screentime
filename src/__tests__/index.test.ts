import type { IosUsageLog } from '../CattusScreenTime.types';

type Api = typeof import('../index');

/** Loads the public API with a given `Platform.OS` and native module. */
function load(os: 'ios' | 'android' | 'web', nativeModule: Record<string, jest.Mock> | null): Api {
  jest.resetModules();
  jest.doMock('react-native', () => ({ Platform: { OS: os } }));
  jest.doMock('../CattusScreenTimeModule', () => ({ __esModule: true, default: nativeModule }));
  return require('../index') as Api;
}

const resolved = (value: unknown) => jest.fn().mockResolvedValue(value);

describe('unsupported platform', () => {
  it('reports unavailable instead of throwing for the status', async () => {
    const api = load('web', null);
    expect(api.isSupported()).toBe(false);
    await expect(api.getAuthorizationStatus()).resolves.toBe('unavailable');
  });

  it('rejects measurement with ERR_UNSUPPORTED_PLATFORM', async () => {
    const api = load('web', null);
    await expect(api.getDailyUsage()).rejects.toMatchObject({ code: 'ERR_UNSUPPORTED_PLATFORM' });
  });

  it('rejects when the native module is missing from the build', async () => {
    const api = load('ios', null);
    await expect(api.requestAuthorization()).rejects.toMatchObject({
      code: 'ERR_UNSUPPORTED_PLATFORM',
    });
  });
});

describe('iOS', () => {
  const now = new Date(2026, 9, 20, 12).getTime();
  const reg = new Date(2026, 9, 1, 9).getTime() / 1000;
  const usageLog: IosUsageLog = {
    bucketMinutes: 15,
    maxMinutes: 900,
    monitoringActive: true,
    registrations: [{ registeredAt: reg, signature: 'sig' }],
    events: [
      { bucketMinutes: 30, firedAt: new Date(2026, 9, 20, 10).getTime() / 1000, registeredAt: reg },
    ],
  };

  beforeEach(() => jest.spyOn(Date, 'now').mockReturnValue(now));
  afterEach(() => jest.restoreAllMocks());

  it('rejects ERR_NOT_AUTHORIZED without reading the log when permission is off', async () => {
    const getUsageLog = resolved(usageLog);
    const api = load('ios', { getAuthorizationStatus: resolved('denied'), getUsageLog });
    await expect(api.getDailyUsage()).rejects.toMatchObject({ code: 'ERR_NOT_AUTHORIZED' });
    expect(getUsageLog).not.toHaveBeenCalled();
  });

  it('computes the days from the native log', async () => {
    const api = load('ios', {
      getAuthorizationStatus: resolved('approved'),
      getUsageLog: resolved(usageLog),
    });
    const days = await api.getDailyUsage({ days: 2 });
    expect(days).toEqual([
      { date: '2026-10-19', minutes: 0, granularityMinutes: 15, coverage: 'full' },
      { date: '2026-10-20', minutes: 30, granularityMinutes: 15, coverage: 'full' },
    ]);
  });

  it('adds the platform to the selection', async () => {
    const api = load('ios', {
      getSelection: resolved({ applications: 2, categories: 1, webDomains: 0 }),
    });
    await expect(api.getSelection()).resolves.toEqual({
      platform: 'ios',
      applications: 2,
      categories: 1,
      webDomains: 0,
    });
  });

  it('resolves null when the picker is cancelled', async () => {
    const api = load('ios', { presentAppPicker: resolved(null) });
    await expect(api.presentAppPicker()).resolves.toBeNull();
  });

  it('rejects Android-only functions with ERR_UNSUPPORTED_PLATFORM', async () => {
    const api = load('ios', {});
    await expect(api.getInstalledApps()).rejects.toMatchObject({
      code: 'ERR_UNSUPPORTED_PLATFORM',
    });
    await expect(api.getAppUsage()).rejects.toMatchObject({ code: 'ERR_UNSUPPORTED_PLATFORM' });
    await expect(api.setSelectedPackages('all')).rejects.toMatchObject({
      code: 'ERR_UNSUPPORTED_PLATFORM',
    });
  });
});

describe('Android', () => {
  it('passes days to the native module and adds the granularity', async () => {
    const getDailyUsage = resolved([
      { date: '2026-10-19', minutes: 42, coverage: 'full' },
      { date: '2026-10-20', minutes: 7, coverage: 'partial' },
    ]);
    const api = load('android', { getDailyUsage });
    const days = await api.getDailyUsage({ days: 2 });
    expect(getDailyUsage).toHaveBeenCalledWith(2);
    expect(days).toEqual([
      { date: '2026-10-19', minutes: 42, granularityMinutes: 1, coverage: 'full' },
      { date: '2026-10-20', minutes: 7, granularityMinutes: 1, coverage: 'partial' },
    ]);
  });

  it('passes a revoked permission through as ERR_NOT_AUTHORIZED', async () => {
    const revoked = Object.assign(new Error('Usage Access is off'), { code: 'ERR_NOT_AUTHORIZED' });
    const api = load('android', { getDailyUsage: jest.fn().mockRejectedValue(revoked) });
    await expect(api.getDailyUsage()).rejects.toMatchObject({ code: 'ERR_NOT_AUTHORIZED' });
  });

  it("sends null for 'all' when saving the selection", async () => {
    const setSelectedPackages = resolved({ allApps: true, packages: [] });
    const api = load('android', { setSelectedPackages });
    await expect(api.setSelectedPackages('all')).resolves.toEqual({
      platform: 'android',
      allApps: true,
      packages: [],
    });
    expect(setSelectedPackages).toHaveBeenCalledWith(null);
  });

  it('resolves startMonitoring without native work', async () => {
    const api = load('android', {});
    await expect(api.startMonitoring()).resolves.toBe(true);
  });

  it('rejects iOS-only functions with ERR_UNSUPPORTED_PLATFORM', async () => {
    const api = load('android', {});
    await expect(api.presentAppPicker()).rejects.toMatchObject({
      code: 'ERR_UNSUPPORTED_PLATFORM',
    });
  });
});

describe('argument checks', () => {
  it.each([0, 31, 1.5, Number.NaN])('rejects days = %p', async (days) => {
    const api = load('android', { getDailyUsage: resolved([]) });
    await expect(api.getDailyUsage({ days })).rejects.toBeInstanceOf(RangeError);
  });

  it.each([1, -30, -0.5])('rejects dayOffset = %p', async (dayOffset) => {
    const api = load('android', { getAppUsage: resolved({}) });
    await expect(api.getAppUsage({ dayOffset })).rejects.toBeInstanceOf(RangeError);
  });
});

describe('hasErrorCode', () => {
  it('matches errors from both layers by code', () => {
    const api = load('android', {});
    expect(
      api.hasErrorCode(
        new api.CattusScreenTimeError('ERR_NOT_AUTHORIZED', 'x'),
        'ERR_NOT_AUTHORIZED'
      )
    ).toBe(true);
    expect(api.hasErrorCode({ code: 'ERR_USER_LOCKED' }, 'ERR_USER_LOCKED')).toBe(true);
    expect(api.hasErrorCode(new Error('x'), 'ERR_NOT_AUTHORIZED')).toBe(false);
    expect(api.hasErrorCode(null, 'ERR_NOT_AUTHORIZED')).toBe(false);
  });
});
