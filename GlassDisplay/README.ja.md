# Glass Display

Macの画面をAndroidグラスへ送って表示するためのアプリです。<br>
USB接続中は`adb`経由、USBがない場合はキー同期済みのWi-Fi(同一LAN)またはBLE経由で通信します。

[English](README.md) | 日本語 | [简体中文](README.zh-Hans.md) | [繁體中文](README.zh-Hant.md) | [한국어](README.ko.md)

## 必要なもの

- Android SDK Platform-Tools (`adb`)
- macOSのScreen Recording権限
- BetterDisplay（任意。Mac側の解像度切り替えに使います）

Android Studio経由で`adb`を入れていてPATHにない場合は、フルパスで指定します。

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/distribution/GlassDisplay.apk
ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb" ./host/scripts/glass-stream.sh
```

## Glasses (Android) へインストール

AndroidグラスをUSBでMacへ接続し、USBデバッグを許可します。

```bash
adb install -r app/distribution/GlassDisplay.apk
adb shell am start -n bio.aq.glassdisplay/.MainActivity
```

署名違いで更新できない場合は、一度削除してから入れ直します。

```bash
adb uninstall bio.aq.glassdisplay
adb install app/distribution/GlassDisplay.apk
```

## Macへインストール / 実行

### 手動実行する

```bash
./host/scripts/glass-stream.sh
```

このスクリプトは常駐し、Androidグラスの接続状態を見ながら送信を続けます。<br>
初回はmacOSのScreen Recording権限を求められます。

### ログイン時に起動する常駐サービスとして登録

```bash
./host/scripts/install-launch-agent.sh
```

## BetterDisplay

BetterDisplayをMacへインストールしている場合、Androidグラス側のメニューからMac側の仮想ディスプレイ解像度を切り替えられます。<br>
同じ操作をMac側から直接実行することもできます。

```bash
./host/sender/glass-betterdisplay-resolution.sh 480x640
./host/sender/glass-betterdisplay-resolution.sh 480x320
./host/sender/glass-betterdisplay-resolution.sh off
```

BetterDisplayがない場合、解像度変更コマンドは失敗します。<br>
画面表示自体はBetterDisplayなしでも実行できます。

## 解像度モード

Glass Displayの画面表示は、受信したフレームの幅と高さに合わせて表示します。<br>
そのため、送信側で指定した任意の解像度を扱えます。<br>
Androidグラスの解像度では画面が小さすぎる場合は上下に分割し、マウスカーソル周辺を拡大して上側に表示します。

Androidグラス側のResolutionメニューは、Mac側のBetterDisplay仮想ディスプレイを切り替えるためのプリセットです。<br>
標準では次の項目を用意しています。

- `480x640`: デバイスの最大表示向け。
- `480x320`: splitモード向け。上下分割表示の1枠に合わせた解像度です。
- `off`: BetterDisplay仮想ディスプレイを無効化します。

Enterでメニューを開き、Resolutionからプリセットを選びます。

## 通信方式

Transportの標準設定はAutoです。USB/adb、Wi-Fi(同一LAN)、BLEの順に試します。Wi-FiまたはBLEを明示的に固定することもできます。

通信方式を固定したい場合:

```bash
./host/scripts/glass-stream.sh --transport auto
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport wifi
./host/scripts/glass-stream.sh --transport ble
```

Wi-FiとBLEを使うには、一度USB接続して暗号化キーをAndroidグラスへ同期しておく必要があります。

## Wi-Fiモード

Wi-FiモードはadbモードとおなじTCPプロトコルでLAN経由送信するため、BLEより大幅に高速です。<br>
先にAndroidのWi-Fi設定から、グラスをMacと同じWi-Fiネットワークへ接続しておきます。<br>
adbセットアップ時に`glass-stream.sh`がグラスの現在のWi-Fi LAN IP(`wlan0`)を読み取り、ストリームキーの隣にキャッシュします(`keys/<serial>.ip`。USB接続の度に更新)。

USBを外すと、senderはキャッシュ済みIPの`tcp:19400`へ直接接続します。フレームはネットワーク上でもAES-256-GCMで暗号化されたままです。<br>
Wi-Fi経由では最大2台のMacから同時送信でき、Display modeのSplitで両方を表示できます。<br>
グラスのIPが変わった場合(DHCP)は、一度USBを接続するとキャッシュが更新されます。その間もBLEフォールバックは利用できます。<br>
Auto、Wi-Fi、BLEの切り替えはグラスのメニューから行います: Enter → Transport。

### 2台のMacから同時に送信する

各Macは個別の暗号化キーを使うため、最初に1台ずつUSB接続して、それぞれで`glass-stream.sh`を起動してください。これにより両方のMacのキーがグラスへ登録されます。<br>
登録後はグラスと両方のMacを同じWi-Fiへ接続し、各Macで`glass-stream.sh --transport wifi`を実行します。グラス側のDisplay modeをSplitにすると2台を同時表示できます。

USB/adbはUSB接続中の1台だけに使われます。2台同時表示では、2台ともWi-Fiを使うか、USB/adbの1台とWi-Fiの1台を組み合わせてください。アプリを再インストールした後に`No stream key installed for this host`と表示された場合は、そのMacをもう一度USB接続してキーを再登録します。

## 暗号化

フレーム通信はAES-256-GCMで暗号化されます。<br>
Macごとに別のストリームキーを使います。USB/adbでペアリングすると、そのMac用の新しいキーを作成し、送信前に同期します。

キーはMac側では次の場所に保存されます。

```text
~/Library/Application Support/GlassDisplay/keys
```

USBを外した後のBLE通信では、最後に同期されたキーを使います。<br>
BLEで認証エラーが出る場合は、AndroidグラスをUSB接続してからサービスを再起動してください。

```bash
./host/scripts/glass-service.sh restart
```

## 画面メニュー

- Enter: メニューを開く / 決定
- Right + Down: 次の項目
- Left + Up: 前の項目
- Resolution: BetterDisplayプリセット `480x640`、`480x320`、`off`
- Display mode: Full / Split
- Transport: Auto / Wi-Fi / BLE(AutoはUSB → Wi-Fi → BLEの順に試行)

Splitモードでは、複数の送信元(Wi-FiまたはBLE、最大2つ)がある場合に上下分割で表示します。

## アンインストール

Androidグラスからアプリを削除:

```bash
adb uninstall bio.aq.glassdisplay
```

Macの常駐サービスを削除:

```bash
./host/scripts/glass-service.sh uninstall
```

ログや暗号化キーも消す場合:

```bash
rm -rf "$HOME/Library/Logs/GlassDisplay"
rm -rf "$HOME/Library/Application Support/GlassDisplay"
```

## トラブルシュート

サービス状態の確認:

```bash
./host/scripts/glass-service.sh status
./host/scripts/glass-service.sh doctor
```

ログの確認:

```bash
./host/scripts/glass-service.sh logs
```

サービスの再起動:

```bash
./host/scripts/glass-service.sh restart
```

ログファイル:

- `~/Library/Logs/GlassDisplay/glass-stream.out.log`
- `~/Library/Logs/GlassDisplay/glass-stream.err.log`
