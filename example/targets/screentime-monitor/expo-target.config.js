/** @type {import('@bacons/apple-targets/app.plugin').ConfigFunction} */
module.exports = (config) => ({
  type: 'device-activity-monitor',
  deploymentTarget: '16.4',
  frameworks: ['DeviceActivity', 'FamilyControls'],
  entitlements: {
    'com.apple.developer.family-controls': true,
    // The extension and the app must share the same App Group.
    'com.apple.security.application-groups':
      config.ios.entitlements['com.apple.security.application-groups'],
  },
});
