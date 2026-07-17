# Glass Display

Glass Display streams your Mac screen to Android glasses.<br>
It uses `adb` while USB is connected, then falls back to Wi-Fi (same LAN) or BLE when a synced stream key is available.

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

The default Transport setting is Auto, which tries USB/adb, Wi-Fi (same LAN), then BLE in that order. Wi-Fi and BLE can also be selected explicitly.

To force a transport:

```bash
./host/scripts/glass-stream.sh --transport auto
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport wifi
./host/scripts/glass-stream.sh --transport ble
```

Wi-Fi and BLE require one USB connection first so the encryption key can be synced to the Android glasses.

## Wi-Fi Mode

Wi-Fi mode streams over the LAN with the same TCP protocol as the adb mode, so it is much faster than BLE.<br>
Connect the glasses to the same Wi-Fi network as the Mac from the Android Wi-Fi settings first.<br>
During adb setup, `glass-stream.sh` reads the glasses' current Wi-Fi LAN IP (`wlan0`) and caches it next to the stream key (`keys/<serial>.ip`, refreshed on every USB connection).

After USB is unplugged, the sender connects directly to the cached IP on `tcp:19400`. Frames stay AES-256-GCM encrypted on the network.<br>
Up to 2 Macs can stream simultaneously over Wi-Fi; use Display mode Split to show both.<br>
If the glasses' IP changes (DHCP), reconnect USB once to refresh the cache — BLE keeps working as a fallback in the meantime.<br>
Choose Auto, Wi-Fi, or BLE from the glasses menu: Enter → Transport.

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
- Transport: Auto / Wi-Fi / BLE (Auto tries USB → Wi-Fi → BLE)

Split mode shows multiple sources (Wi-Fi or BLE, up to 2) in a vertical split view when more than one source is connected.

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
