# Dual Audio

**Play the same media on two Bluetooth audio devices at once — where your phone allows it.**

Dual Audio is a small, open-source Android app that helps you connect two Bluetooth speakers or headphones and send the phone's media audio to both. It uses the multi-output features your phone actually has, shows you exactly what is supported, and never pretends that software can add a radio capability the firmware does not contain.

[**Download the latest APK**](https://github.com/CocoCopi/dualaudio/releases/latest)

---

## The honest part first

Most Android phones allow **one classic Bluetooth (A2DP) audio connection at a time**. This is a Bluetooth stack and vendor configuration limit, not something an ordinary app can bypass. If your phone is one of them, connecting a second pair of headphones will disconnect the first, no matter which app you install.

What Dual Audio does:

| Path | What it uses | Where it works |
| --- | --- | --- |
| **System multi-output** | Android's native output switcher (`MediaRouter2`) | Android 14+; Samsung Galaxy and other phones with multi-output support |
| **Forced dual routing** | Combined audio routing through **Shizuku** (ADB shell UID, no root) | Android 12+; only when the phone's Bluetooth stack permits two connected audio devices |
| **LE Audio / Auracast** | Android 13+ LE Audio sharing, exposed through the system UI | Phones and earbuds with LE Audio hardware support |

The app detects these capabilities and tells you which path is available on your device. It does not request root and does not require a PC after the one-time Shizuku setup.

Samsung's **Dual Audio** feature is the system output switcher with two audio devices ticked. Dual Audio opens that switcher for you. It cannot toggle a hidden Samsung setting on phones that do not have the feature.

## Features

- Choose two Bluetooth outputs and see live connection state for each.
- Open the native output switcher with one tap.
- Optional forced routing through Shizuku, with automatic re-apply after reconnects.
- Optional one-tap Bluetooth connect/disconnect through Shizuku (experimental).
- Capability panel: combined routing, native switcher, Samsung, LE Audio, LE broadcast, and whether two A2DP sinks are actually connected.
- Built-in test tone, so you can hear whether both outputs are really playing.
- Diagnostics report you can copy for bug reports.
- Foreground status notification while forced routing is active.
- No internet permission, no analytics, no accounts.

## Requirements

- Android 12 (API 31) or newer. Android 14+ gives the best experience.
- Two paired Bluetooth audio devices.
- For forced routing: [Shizuku](https://shizuku.rikka.app/) 11 or newer, started through Wireless debugging or root.
- The phone's Bluetooth stack must allow two simultaneous audio connections. Many do not.

## Install

1. Download `DualAudio-x.y.z.apk` from [Releases](https://github.com/CocoCopi/dualaudio/releases/latest).
2. Allow installing apps from your browser or file manager when prompted.
3. Open Dual Audio and grant **Nearby devices** (Bluetooth) and, on Android 13+, **Notifications**.
4. Pair both audio devices in system Bluetooth settings.
5. Pick them as **Output A** and **Output B**.
6. Tap the main button:
   - Without Shizuku, this opens the system output switcher. Select both devices there.
   - With Shizuku ready, this applies combined media routing to both outputs.

### Setting up Shizuku (optional)

1. Install Shizuku from the Play Store or [GitHub](https://github.com/RikkaApps/Shizuku).
2. Enable Developer options and **Wireless debugging**.
3. In Shizuku, tap **Start via Wireless debugging** and complete pairing.
4. Return to Dual Audio, tap **Grant access**, and approve the prompt.
5. Tap **Enable with Shizuku** (or **Apply forced routing**).

Shizuku runs a tiny helper process with the ADB shell UID. That process has `MODIFY_AUDIO_ROUTING`, so it can call Android's combined-routing API. No root and no permanent ADB connection are needed. You can revoke access at any time in Shizuku.

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_CONNECT` (Nearby devices) | List paired audio devices and read their connection state. |
| `POST_NOTIFICATIONS` | Show the ongoing routing notification. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the optional routing helper visible and alive. |

Dual Audio declares **no** `INTERNET` permission. Nothing leaves your phone. Device addresses and names stay in local app preferences.

## Build from source

Requirements: JDK 17, Android SDK Platform 35, Build-Tools 35.0.0.

```bash
git clone https://github.com/CocoCopi/dualaudio.git
cd dualaudio
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Release builds are signed from environment variables:

```bash
export DUALAUDIO_KEYSTORE_FILE=/absolute/path/release.jks
export DUALAUDIO_KEYSTORE_PASSWORD=...
export DUALAUDIO_KEY_ALIAS=...
export DUALAUDIO_KEY_PASSWORD=...
export DUALAUDIO_VERSION_NAME=1.0.0
export DUALAUDIO_VERSION_CODE=1
./gradlew :app:assembleRelease
```

GitHub Actions does this automatically for every `v*` tag. The workflow decodes the keystore from repository secrets, builds, verifies the signature, and publishes the APK and its SHA-256 checksum to the GitHub Release.

## Verifying a release

```bash
sha256sum DualAudio-1.0.0.apk
apksigner verify --print-certs DualAudio-1.0.0.apk
```

Signing certificate SHA-256:

```text
78:2E:64:3E:F3:34:08:57:3E:E4:F0:56:CB:28:3C:43:3D:E4:D5:D5:BC:65:E0:70:20:63:BF:C2:2B:97:8D:87
```

## How it works

- **System switcher:** `MediaRouter2.showSystemOutputSwitcher()` on Android 14+, with a fallback to the system media-output panel intent on older releases.
- **Forced routing:** AudioService's `setPreferredDevicesForStrategy()` with the media strategy. It is a privileged API (`MODIFY_AUDIO_ROUTING`), so it runs in a Shizuku user service as the shell user. Android mixes the stream once and duplicates it to the preferred outputs.
- **Capability checks:** `AudioManager`, `BluetoothAdapter`, `BluetoothA2dp`, `BluetoothLeAudio`, and the audio output device list.

None of this raises the Bluetooth stack's own limit on connected audio devices. The app observes that limit and reports it instead of hiding failures.

## Project layout

```text
app/src/main/
├── aidl/…/IAudioRoutingService.aidl      # App ↔ Shizuku helper contract
├── java/…/MainActivity.kt                # UI and orchestration
├── java/…/BluetoothController.kt         # Public Bluetooth state APIs
├── java/…/DevicePickerSheet.kt           # Paired-device picker
├── java/…/DualAudioService.kt            # Foreground status/reapply service
├── java/…/TestTonePlayer.kt              # Two-note audio path test
├── java/…/routing/AudioRouter.java        # Combined routing via AudioService
├── java/…/routing/AudioRoutingUserService.java
├── java/…/routing/BluetoothConnector.java # Optional shell-UID connect
├── java/…/routing/ShizukuManager.kt
└── res/                                   # Material 3 UI
```

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

The routing approach is informed by and derived from the GPL-3.0 project
[xTudoS/dual-audio](https://github.com/xTudoS/dual-audio). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Dual Audio is not affiliated with Google, Samsung, or the Shizuku project.
