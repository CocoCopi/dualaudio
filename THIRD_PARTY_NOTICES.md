# Third-party notices

Dual Audio is distributed under the GNU General Public License v3.0.

## Shizuku API

- Project: https://github.com/RikkaApps/Shizuku-API
- Artifact: `dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`
- License: Apache License 2.0

The Shizuku API is used to bind an optional user service running with the ADB
shell UID. Shizuku itself is a separate application and is not bundled here.

## AndroidX and Material Components

- AndroidX Core, AppCompat, Activity, Lifecycle, RecyclerView
- Material Components for Android
- License: Apache License 2.0

## Prior art

The combined-routing approach, including the AudioService reflection and the
Shizuku user-service pattern, is informed by and derived from:

- Project: https://github.com/xTudoS/dual-audio
- License: GNU General Public License v3.0

Dual Audio is an independent implementation with its own UI, device management,
capability diagnostics, and routing watcher, but it follows the same privileged
API path described by that project.

## Android

Android is a trademark of Google LLC. Samsung and Galaxy are trademarks of
Samsung Electronics. All other trademarks are the property of their respective
owners.
