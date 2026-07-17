# Glass Display

Glass Display 是一個將 Mac 畫面串流到 Android 眼鏡並顯示的應用程式。<br>
USB 連線時透過 `adb` 通訊；沒有 USB 時，如果已同步金鑰，則透過 Wi-Fi（同一區域網路）或 BLE 通訊。

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-Hans.md) | 繁體中文 | [한국어](README.ko.md)

## 需求

- Android SDK Platform-Tools (`adb`)
- macOS 螢幕錄製權限
- BetterDisplay（選用，用於在 Mac 端切換解析度）

如果 `adb` 是透過 Android Studio 安裝的，而且不在 `PATH` 中，請使用完整路徑：

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/distribution/GlassDisplay.apk
ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb" ./host/scripts/glass-stream.sh
```

## 安裝到眼鏡 (Android)

用 USB 將 Android 眼鏡連接到 Mac，並允許 USB 偵錯。

```bash
adb install -r app/distribution/GlassDisplay.apk
adb shell am start -n bio.aq.glassdisplay/.MainActivity
```

如果因為簽章不同而無法更新，請先解除安裝舊應用程式再安裝。

```bash
adb uninstall bio.aq.glassdisplay
adb install app/distribution/GlassDisplay.apk
```

## 在 Mac 上安裝 / 執行

手動執行：

```bash
./host/scripts/glass-stream.sh
```

這個腳本會常駐執行，並依照 Android 眼鏡的連線狀態持續傳送畫面。<br>
首次執行時，macOS 會要求螢幕錄製權限。

註冊為登入時啟動的常駐服務：

```bash
./host/scripts/install-launch-agent.sh
```

## BetterDisplay

如果 Mac 上安裝了 BetterDisplay，可以從 Android 眼鏡端選單切換 Mac 端虛擬顯示器解析度。<br>
也可以直接在 Mac 上執行相同操作。

```bash
./host/sender/glass-betterdisplay-resolution.sh 480x640
./host/sender/glass-betterdisplay-resolution.sh 480x320
./host/sender/glass-betterdisplay-resolution.sh off
```

如果沒有 BetterDisplay，解析度切換命令會失敗，但畫面串流本身仍可使用。

## 解析度模式

Glass Display 會依照接收到的影格寬高顯示串流。<br>
因此，它可以處理傳送端指定的任意解析度。<br>
當 Android 眼鏡的解析度太小無法看清時，畫面會被上下分割，並在上半部顯示滑鼠游標周圍的放大區域。

Android 眼鏡端的 Resolution 選單用於切換 Mac 端 BetterDisplay 虛擬顯示器預設。<br>
預設項目如下：

- `480x640`: 面向裝置最大顯示區域。
- `480x320`: 面向 split 模式，符合上下分割顯示中的一半。
- `off`: 關閉 BetterDisplay 虛擬顯示器。

按 Enter 開啟選單，然後在 Resolution 中選擇預設。

## 通訊方式

Transport 預設設定為 Auto，依序嘗試 USB/adb、Wi-Fi（同一區域網路）和 BLE。也可以明確固定為 Wi-Fi 或 BLE。

固定通訊方式：

```bash
./host/scripts/glass-stream.sh --transport auto
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport wifi
./host/scripts/glass-stream.sh --transport ble
```

使用 Wi-Fi 和 BLE 前，需要先透過一次 USB 連線將加密金鑰同步到 Android 眼鏡。

## Wi-Fi 模式

Wi-Fi 模式使用與 adb 模式相同的 TCP 協定透過區域網路傳送，因此比 BLE 快得多。<br>
請先從 Android Wi-Fi 設定將眼鏡連線到與 Mac 相同的 Wi-Fi 網路。<br>
在 adb 設定期間，`glass-stream.sh` 會讀取眼鏡目前的 Wi-Fi 區域網路 IP（`wlan0`），並快取到串流金鑰旁邊（`keys/<serial>.ip`，每次 USB 連線時更新）。

拔掉 USB 後，sender 會直接連線到快取 IP 的 `tcp:19400`。影格在網路上仍保持 AES-256-GCM 加密。<br>
最多可有 2 台 Mac 透過 Wi-Fi 同時傳送，使用 Display mode 的 Split 可同時顯示兩者。<br>
如果眼鏡的 IP 發生變化（DHCP），重新連線一次 USB 即可更新快取，期間 BLE 回退仍然可用。<br>
在眼鏡選單中切換 Auto、Wi-Fi 和 BLE：Enter → Transport。

## 加密

影格通訊使用 AES-256-GCM 加密。<br>
每台 Mac 使用各自的串流金鑰。透過 USB/adb 配對時，會為這台 Mac 建立新金鑰，並在開始傳送前同步。

Mac 端金鑰保存在：

```text
~/Library/Application Support/GlassDisplay/keys
```

USB 中斷後的 BLE 通訊會使用最後一次同步的金鑰。<br>
如果 BLE 認證失敗，請用 USB 連接 Android 眼鏡，然後重新啟動服務。

```bash
./host/scripts/glass-service.sh restart
```

## 畫面選單

- Enter: 開啟選單 / 確認
- Right + Down: 下一項
- Left + Up: 上一項
- Resolution: BetterDisplay 預設 `480x640`、`480x320`、`off`
- Display mode: Full / Split
- Transport: Auto / Wi-Fi / BLE（Auto 依序嘗試 USB → Wi-Fi → BLE）

Split 模式下，如果存在多個傳送來源（Wi-Fi 或 BLE，最多 2 個），會以上下分割方式顯示。

## 解除安裝

從 Android 眼鏡刪除應用程式：

```bash
adb uninstall bio.aq.glassdisplay
```

刪除 Mac 常駐服務：

```bash
./host/scripts/glass-service.sh uninstall
```

同時刪除日誌和加密金鑰：

```bash
rm -rf "$HOME/Library/Logs/GlassDisplay"
rm -rf "$HOME/Library/Application Support/GlassDisplay"
```

## 疑難排解

檢查服務狀態：

```bash
./host/scripts/glass-service.sh status
./host/scripts/glass-service.sh doctor
```

查看日誌：

```bash
./host/scripts/glass-service.sh logs
```

重新啟動服務：

```bash
./host/scripts/glass-service.sh restart
```

日誌檔：

- `~/Library/Logs/GlassDisplay/glass-stream.out.log`
- `~/Library/Logs/GlassDisplay/glass-stream.err.log`
