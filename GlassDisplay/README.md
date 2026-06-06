# Glass Display

Glass Display streams your Mac screen to Android glasses.<br>
It uses `adb` while USB is connected, then falls back to BLE when a synced stream key is available.

English | [日本語](README.ja.md) | [简体中文](README.zh-Hans.md) | [繁體中文](README.zh-Hant.md) | [한국어](README.ko.md)

## Requirements

- Android SDK Platform-Tools (`adb`)
- macOS Screen Recording permission
- BetterDisplay (optional, used for Mac-side resolution switching)

If `adb` was installed through Android Studio and is not on your `PATH`, use the full path:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/distribution/GlassDisplay.apk
ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb" ./host/scripts/glass-stream.sh
```

## Install On Glasses (Android)

Connect the Android glasses to your Mac over USB and allow USB debugging.

```bash
adb install -r app/distribution/GlassDisplay.apk
adb shell am start -n bio.aq.glassdisplay/.MainActivity
```

If the update fails because the signing key changed, uninstall the old app first.

```bash
adb uninstall bio.aq.glassdisplay
adb install app/distribution/GlassDisplay.apk
```

## Install And Run On Mac

Run manually:

```bash
./host/scripts/glass-stream.sh
```

This script stays running and streams while watching the Android glasses connection state.<br>
macOS asks for Screen Recording permission on first run.

Install as a LaunchAgent that starts at login:

```bash
./host/scripts/install-launch-agent.sh
```

## BetterDisplay

If BetterDisplay is installed on your Mac, you can switch the Mac virtual display resolution from the Android glasses menu.<br>
You can run the same commands directly on the Mac.

```bash
./host/sender/glass-betterdisplay-resolution.sh 480x640
./host/sender/glass-betterdisplay-resolution.sh 480x320
./host/sender/glass-betterdisplay-resolution.sh off
```

Without BetterDisplay, resolution switching fails, but screen streaming still works.

## Resolution Modes

Glass Display renders the stream using the received frame width and height.<br>
That means it can handle any resolution chosen by the sender.<br>
When the Android glasses resolution is too small, it splits the view vertically and shows a zoomed region around the mouse cursor in the upper half.

The Resolution menu on the Android glasses switches BetterDisplay virtual display presets on the Mac.<br>
The default presets are:

- `480x640`: for the device's maximum display area.
- `480x320`: for split mode, matching one half of the vertical split view.
- `off`: disables the BetterDisplay virtual display.

Press Enter to open the menu, then choose a preset from Resolution.

## Transport

By default, `adb` is preferred.<br>
While USB is connected, the app uses `adb forward tcp:19400 tcp:19400`.<br>
When USB disconnects, it switches to BLE if a stream key has already been synced.

To force a transport:

```bash
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport ble
```

BLE requires one USB connection first so the encryption key can be synced to the Android glasses.

## Encryption

Frame transport is encrypted with AES-256-GCM.<br>
Each Mac gets its own stream key. USB/adb pairing creates a fresh key for that Mac and syncs it before streaming.

Keys are stored on the Mac here:

```text
~/Library/Application Support/GlassDisplay/keys
```

BLE uses the last synced key after USB is disconnected.<br>
If BLE authentication fails, connect the Android glasses over USB and restart the service.

```bash
./host/scripts/glass-service.sh restart
```

## On-Screen Menu

- Enter: open menu / confirm
- Right + Down: next item
- Left + Up: previous item
- Resolution: BetterDisplay presets `480x640`, `480x320`, `off`
- Display mode: Full / Split

Split mode shows multiple BLE sources in a vertical split view when more than one source is connected.

## Uninstall

Remove the app from the Android glasses:

```bash
adb uninstall bio.aq.glassdisplay
```

Remove the Mac LaunchAgent:

```bash
./host/scripts/glass-service.sh uninstall
```

Remove logs and encryption keys:

```bash
rm -rf "$HOME/Library/Logs/GlassDisplay"
rm -rf "$HOME/Library/Application Support/GlassDisplay"
```

## Troubleshooting

Check service status:

```bash
./host/scripts/glass-service.sh status
./host/scripts/glass-service.sh doctor
```

Show logs:

```bash
./host/scripts/glass-service.sh logs
```

Restart the service:

```bash
./host/scripts/glass-service.sh restart
```

Log files:

- `~/Library/Logs/GlassDisplay/glass-stream.out.log`
- `~/Library/Logs/GlassDisplay/glass-stream.err.log`
