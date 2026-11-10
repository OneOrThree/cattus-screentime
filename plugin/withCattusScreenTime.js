// Expo config plugin for cattus-screentime.
//
// iOS: adds the Family Controls entitlement, adds the App Group to the app's entitlements
// and writes the App Group to Info.plist (`CattusScreenTimeAppGroup`), where the native
// module reads it. The DeviceActivityMonitor extension is a separate target; see README.
//
// Android: nothing to do. The library manifest declares PACKAGE_USAGE_STATS and the
// launcher-intent query, and the manifest merger adds them to the app.
const { createRunOncePlugin } = require('expo/config-plugins');

const pkg = require('../package.json');

const FAMILY_CONTROLS = 'com.apple.developer.family-controls';
const APP_GROUPS = 'com.apple.security.application-groups';
const INFO_PLIST_KEY = 'CattusScreenTimeAppGroup';

function withCattusScreenTime(config, props) {
  const appGroup = props && props.appGroup;
  if (typeof appGroup !== 'string' || !appGroup.startsWith('group.')) {
    throw new Error(
      'cattus-screentime: set the `appGroup` option to an App Group identifier that starts with "group.", ' +
        'for example ["cattus-screentime", { "appGroup": "group.com.example.app" }].'
    );
  }

  config.ios = config.ios || {};
  const entitlements = { ...(config.ios.entitlements || {}) };
  entitlements[FAMILY_CONTROLS] = true;
  const groups = new Set(entitlements[APP_GROUPS] || []);
  groups.add(appGroup);
  entitlements[APP_GROUPS] = [...groups];
  config.ios.entitlements = entitlements;
  config.ios.infoPlist = { ...(config.ios.infoPlist || {}), [INFO_PLIST_KEY]: appGroup };
  return config;
}

module.exports = createRunOncePlugin(withCattusScreenTime, pkg.name, pkg.version);
