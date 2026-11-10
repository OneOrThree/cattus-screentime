import {
  type AppUsageBreakdown,
  type AuthorizationStatus,
  type DailyUsage,
  type InstalledApp,
  type Selection,
  getAppUsage,
  getAuthorizationStatus,
  getDailyUsage,
  getInstalledApps,
  getSelection,
  openUsageAccessSettings,
  presentAppPicker,
  requestAuthorization,
  reset,
  setSelectedPackages,
  startMonitoring,
} from 'cattus-screentime';
import { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Button,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';

export default function App() {
  const [status, setStatus] = useState<AuthorizationStatus>('notDetermined');
  const [selection, setSelection] = useState<Selection | null>(null);
  const [days, setDays] = useState<DailyUsage[]>([]);
  const [apps, setApps] = useState<AppUsageBreakdown | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (task: () => Promise<void>) => {
    try {
      setError(null);
      await task();
    } catch (e) {
      const { code, message } = e as { code?: string; message?: string };
      setError(`${code ?? 'ERROR'}: ${message ?? String(e)}`);
    }
  }, []);

  const refresh = useCallback(
    () =>
      run(async () => {
        const current = await getAuthorizationStatus();
        setStatus(current);
        setSelection(await getSelection());
        if (current !== 'approved') {
          setDays([]);
          setApps(null);
          return;
        }
        // Registers monitoring again if iOS dropped it; a no-op otherwise.
        await startMonitoring();
        setDays(await getDailyUsage({ days: 7 }));
        if (Platform.OS === 'android') setApps(await getAppUsage({ dayOffset: 0 }));
      }),
    [run]
  );

  useEffect(() => {
    refresh();
    // Android grants Usage Access in Settings, so read the state again on return.
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => subscription.remove();
  }, [refresh]);

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={styles.content}
      contentInsetAdjustmentBehavior="automatic">
      <Text style={styles.title}>cattus-screentime</Text>

      <Section title="1. Permission">
        <Text style={styles.value}>Status: {status}</Text>
        <Button
          title="Request permission"
          onPress={() => run(async () => setStatus(await requestAuthorization()))}
        />
        {Platform.OS === 'android' && (
          <Button
            title="Open Usage Access settings"
            onPress={() => run(async () => void (await openUsageAccessSettings()))}
          />
        )}
      </Section>

      <Section title="2. Apps to measure">
        <Text style={styles.value}>{describeSelection(selection)}</Text>
        {Platform.OS === 'ios' ? (
          <Button
            title="Choose apps"
            disabled={status !== 'approved'}
            onPress={() =>
              run(async () => {
                const picked = await presentAppPicker();
                if (picked) await refresh();
              })
            }
          />
        ) : (
          <AndroidAppList
            enabled={status === 'approved'}
            selection={selection}
            onSave={(packages) =>
              run(async () => {
                setSelection(await setSelectedPackages(packages));
                await refresh();
              })
            }
          />
        )}
      </Section>

      <Section title="3. Usage, last 7 days">
        {days.length === 0 && <Text style={styles.muted}>Approve permission to see usage.</Text>}
        {days.map((day) => (
          <View key={day.date} style={styles.row}>
            <Text style={styles.value}>{day.date}</Text>
            <Text style={styles.value}>
              {day.minutes == null
                ? 'not measured'
                : day.granularityMinutes > 1
                  ? `≥ ${day.minutes} min`
                  : `${day.minutes} min`}
              {day.coverage === 'partial' ? ' (partial)' : ''}
            </Text>
          </View>
        ))}
        {Platform.OS === 'ios' && days.length > 0 && (
          <Text style={styles.muted}>
            iOS counts in 15-minute steps, so each value is a lower bound.
          </Text>
        )}
        <Button title="Refresh" onPress={refresh} />
      </Section>

      {Platform.OS === 'android' && apps && (
        <Section title="4. Today by app">
          {apps.apps.slice(0, 10).map((app) => (
            <View key={app.packageName} style={styles.row}>
              <Text style={styles.value} numberOfLines={1}>
                {app.label}
              </Text>
              <Text style={styles.value}>{Math.round(app.seconds / 60)} min</Text>
            </View>
          ))}
          <Text style={styles.muted}>
            Other (launcher, system UI): {Math.round(apps.otherSeconds / 60)} min
          </Text>
        </Section>
      )}

      {error && <Text style={styles.error}>{error}</Text>}

      <Section title="Reset">
        <Button
          title="Delete all stored data"
          color="#b3261e"
          onPress={() =>
            run(async () => {
              await reset();
              await refresh();
            })
          }
        />
      </Section>
    </ScrollView>
  );
}

function AndroidAppList(props: {
  enabled: boolean;
  selection: Selection | null;
  onSave: (packages: string[] | 'all') => void;
}) {
  const saved = props.selection?.platform === 'android' ? props.selection : null;
  const [installed, setInstalled] = useState<InstalledApp[]>([]);
  const [allApps, setAllApps] = useState(saved?.allApps ?? true);
  const [checked, setChecked] = useState<Set<string>>(new Set(saved?.packages ?? []));

  useEffect(() => {
    if (props.enabled) getInstalledApps().then(setInstalled, () => setInstalled([]));
  }, [props.enabled]);

  useEffect(() => {
    setAllApps(saved?.allApps ?? true);
    setChecked(new Set(saved?.packages ?? []));
  }, [saved?.allApps, saved?.packages]);

  const toggle = (packageName: string) =>
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(packageName)) next.delete(packageName);
      else next.add(packageName);
      return next;
    });

  return (
    <View>
      <View style={styles.row}>
        <Text style={styles.value}>Measure all apps</Text>
        <Switch value={allApps} onValueChange={setAllApps} />
      </View>
      {!allApps &&
        installed.map((app) => (
          <View key={app.packageName} style={styles.row}>
            <Text style={styles.value} numberOfLines={1}>
              {app.label}
            </Text>
            <Switch
              value={checked.has(app.packageName)}
              onValueChange={() => toggle(app.packageName)}
            />
          </View>
        ))}
      <Button
        title="Save selection"
        disabled={!props.enabled || (!allApps && checked.size === 0)}
        onPress={() => props.onSave(allApps ? 'all' : [...checked])}
      />
    </View>
  );
}

function describeSelection(selection: Selection | null): string {
  if (selection == null) return 'No apps chosen yet.';
  if (selection.platform === 'ios') {
    return `${selection.applications} apps, ${selection.categories} categories, ${selection.webDomains} websites`;
  }
  return selection.allApps ? 'All apps' : `${selection.packages.length} apps`;
}

function Section(props: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{props.title}</Text>
      {props.children}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#f2f2f2' },
  content: { padding: 16, paddingTop: (StatusBar.currentHeight ?? 0) + 16, gap: 12 },
  title: { fontSize: 24, fontWeight: '600' },
  section: { backgroundColor: '#fff', borderRadius: 12, padding: 16, gap: 8 },
  sectionTitle: { fontSize: 17, fontWeight: '600' },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 12 },
  value: { fontSize: 15, flexShrink: 1 },
  muted: { fontSize: 13, color: '#666' },
  error: { color: '#b3261e', fontSize: 14 },
});
