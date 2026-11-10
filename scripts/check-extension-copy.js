#!/usr/bin/env node
// The example app ships its own copy of the DeviceActivityMonitor extension, because
// @bacons/apple-targets compiles only the files inside the target folder. This check
// fails when the copy drifts from the source that users are told to copy.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const source = path.join(root, 'ios-extension', 'DeviceActivityMonitorExtension.swift');
const copy = path.join(root, 'example', 'targets', 'screentime-monitor', 'DeviceActivityMonitorExtension.swift');

if (!fs.readFileSync(source).equals(fs.readFileSync(copy))) {
  console.error(`${path.relative(root, copy)} differs from ${path.relative(root, source)}. Copy the source over it.`);
  process.exit(1);
}
console.log('Extension copy matches ios-extension/.');
