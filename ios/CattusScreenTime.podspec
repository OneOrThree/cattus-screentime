require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'CattusScreenTime'
  s.version        = package['version']
  s.summary        = 'Screen time measurement for Expo: Family Controls and DeviceActivity on iOS, Usage Access on Android.'
  s.description    = 'Expo module that measures how long the user spends in chosen apps. On iOS it presents the FamilyActivityPicker and registers DeviceActivity thresholds that a monitor extension records in an App Group.'
  s.author         = 'OneOrThree'
  s.license        = 'Apache-2.0'
  s.homepage       = 'https://github.com/OneOrThree/cattus-screentime'
  s.platforms      = {
    :ios => '16.4'
  }
  s.source         = { git: 'https://github.com/OneOrThree/cattus-screentime.git' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  s.frameworks = 'FamilyControls', 'DeviceActivity', 'SwiftUI'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "*.{h,m,mm,swift,hpp,cpp}"
end
