# WiFi Data Controller

Vector module (libxposed API 102) that turns mobile data off when Wi-Fi connects and back on when Wi-Fi disconnects.

Package: `net.libane.wifidatacontroller`.

The module loads in `system_server`. It watches Wi-Fi networks and sets the user mobile-data switch with `TelephonyManager.setDataEnabledForReason` (`DATA_ENABLED_REASON_USER`) on the active data SIM. IMS and voice stay up.

## Scope

`META-INF/xposed/scope.list` contains `system` (System Framework / system_server), and `module.prop` sets `staticScope=true`, so Vector keeps that scope. The entry class is `META-INF/xposed/java_init.list`. libxposed API 102 is `compileOnly` and is not packaged into the APK.

## Install

1. Build the debug APK, or download the `wifi-data-controller-debug` artifact from Actions.
2. Install it. Enable the module in Vector if it is not already active.
3. Reboot so Vector reloads the system-server hook.

## Build

GitHub Actions builds on every push to `main`, and can be run by hand from Actions → Build Android APK. That workflow uses JDK 21 and Gradle 9.7.1.

This repo has no Gradle wrapper. Locally, with the Android SDK (platform 37) and Gradle on `PATH`:

```
gradle :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
