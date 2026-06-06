# Glass Display

Glass Display는 Mac 화면을 Android 글래스로 스트리밍해서 표시하는 앱입니다.<br>
USB 연결 중에는 `adb`를 사용하고, USB가 없을 때는 동기화된 스트림 키가 있으면 BLE로 통신합니다.

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-Hans.md) | [繁體中文](README.zh-Hant.md) | 한국어

## 요구 사항

- Android SDK Platform-Tools (`adb`)
- macOS 화면 기록 권한
- BetterDisplay(선택 사항, Mac 쪽 해상도 전환에 사용)

`adb`를 Android Studio를 통해 설치했고 `PATH`에 없다면 전체 경로를 사용합니다.

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/distribution/GlassDisplay.apk
ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb" ./host/scripts/glass-stream.sh
```

## 글래스(Android)에 설치

Android 글래스를 USB로 Mac에 연결하고 USB 디버깅을 허용합니다.

```bash
adb install -r app/distribution/GlassDisplay.apk
adb shell am start -n bio.aq.glassdisplay/.MainActivity
```

서명 차이로 업데이트할 수 없으면 기존 앱을 먼저 삭제한 뒤 다시 설치합니다.

```bash
adb uninstall bio.aq.glassdisplay
adb install app/distribution/GlassDisplay.apk
```

## Mac에 설치 / 실행

수동 실행:

```bash
./host/scripts/glass-stream.sh
```

이 스크립트는 계속 실행되며 Android 글래스의 연결 상태를 확인하면서 화면을 전송합니다.<br>
처음 실행하면 macOS가 화면 기록 권한을 요청합니다.

로그인 시 시작되는 LaunchAgent로 등록:

```bash
./host/scripts/install-launch-agent.sh
```

## BetterDisplay

Mac에 BetterDisplay가 설치되어 있으면 Android 글래스의 메뉴에서 Mac 쪽 가상 디스플레이 해상도를 전환할 수 있습니다.<br>
같은 작업을 Mac에서 직접 실행할 수도 있습니다.

```bash
./host/sender/glass-betterdisplay-resolution.sh 480x640
./host/sender/glass-betterdisplay-resolution.sh 480x320
./host/sender/glass-betterdisplay-resolution.sh off
```

BetterDisplay가 없으면 해상도 변경 명령은 실패하지만 화면 스트리밍 자체는 동작합니다.

## 해상도 모드

Glass Display는 수신한 프레임의 너비와 높이에 맞춰 스트림을 표시합니다.<br>
따라서 송신 쪽에서 지정한 임의의 해상도를 처리할 수 있습니다.<br>
Android 글래스 해상도가 너무 작아 보기 어려울 때는 화면을 위아래로 나누고, 마우스 커서 주변을 확대해 위쪽에 표시합니다.

Android 글래스의 Resolution 메뉴는 Mac 쪽 BetterDisplay 가상 디스플레이 프리셋을 전환합니다.<br>
기본 프리셋은 다음과 같습니다.

- `480x640`: 기기의 최대 표시 영역용.
- `480x320`: split 모드용. 위아래 분할 표시의 한 영역에 맞춘 해상도입니다.
- `off`: BetterDisplay 가상 디스플레이를 비활성화합니다.

Enter로 메뉴를 열고 Resolution에서 프리셋을 선택합니다.

## 통신 방식

기본값은 `adb` 우선입니다.<br>
USB 연결 중에는 `adb forward tcp:19400 tcp:19400`로 전송합니다.<br>
USB가 분리되면 동기화된 키가 있는 경우 BLE로 전환합니다.

통신 방식을 고정하려면:

```bash
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport ble
```

BLE를 사용하려면 먼저 한 번 USB로 연결해서 암호화 키를 Android 글래스에 동기화해야 합니다.

## 암호화

프레임 통신은 AES-256-GCM으로 암호화됩니다.<br>
Mac마다 별도의 스트림 키를 사용합니다. USB/adb로 페어링하면 해당 Mac용 새 키를 만들고, 전송 전에 동기화합니다.

키는 Mac 쪽의 다음 위치에 저장됩니다.

```text
~/Library/Application Support/GlassDisplay/keys
```

USB를 분리한 뒤의 BLE 통신은 마지막으로 동기화된 키를 사용합니다.<br>
BLE 인증 오류가 발생하면 Android 글래스를 USB로 연결한 뒤 서비스를 다시 시작하세요.

```bash
./host/scripts/glass-service.sh restart
```

## 화면 메뉴

- Enter: 메뉴 열기 / 결정
- Right + Down: 다음 항목
- Left + Up: 이전 항목
- Resolution: BetterDisplay 프리셋 `480x640`, `480x320`, `off`
- Display mode: Full / Split

Split 모드에서는 BLE 송신원이 여러 개 있을 때 위아래 분할로 표시합니다.

## 제거

Android 글래스에서 앱 삭제:

```bash
adb uninstall bio.aq.glassdisplay
```

Mac의 LaunchAgent 삭제:

```bash
./host/scripts/glass-service.sh uninstall
```

로그와 암호화 키도 삭제하려면:

```bash
rm -rf "$HOME/Library/Logs/GlassDisplay"
rm -rf "$HOME/Library/Application Support/GlassDisplay"
```

## 문제 해결

서비스 상태 확인:

```bash
./host/scripts/glass-service.sh status
./host/scripts/glass-service.sh doctor
```

로그 확인:

```bash
./host/scripts/glass-service.sh logs
```

서비스 재시작:

```bash
./host/scripts/glass-service.sh restart
```

로그 파일:

- `~/Library/Logs/GlassDisplay/glass-stream.out.log`
- `~/Library/Logs/GlassDisplay/glass-stream.err.log`
