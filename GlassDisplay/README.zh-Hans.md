# Glass Display

Glass Display 是一个将 Mac 屏幕串流到 Android 眼镜并显示的应用。<br>
USB 连接时通过 `adb` 通信；没有 USB 时，如果已同步密钥，则通过 BLE 通信。

[English](README.md) | [日本語](README.ja.md) | 简体中文 | [繁體中文](README.zh-Hant.md) | [한국어](README.ko.md)

## 要求

- Android SDK Platform-Tools (`adb`)
- macOS 屏幕录制权限
- BetterDisplay（可选，用于在 Mac 端切换分辨率）

如果 `adb` 是通过 Android Studio 安装的，并且不在 `PATH` 中，请使用完整路径：

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/distribution/GlassDisplay.apk
ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb" ./host/scripts/glass-stream.sh
```

## 安装到眼镜 (Android)

用 USB 将 Android 眼镜连接到 Mac，并允许 USB 调试。

```bash
adb install -r app/distribution/GlassDisplay.apk
adb shell am start -n bio.aq.glassdisplay/.MainActivity
```

如果因为签名不同而无法更新，请先卸载旧应用再安装。

```bash
adb uninstall bio.aq.glassdisplay
adb install app/distribution/GlassDisplay.apk
```

## 在 Mac 上安装 / 运行

手动运行：

```bash
./host/scripts/glass-stream.sh
```

该脚本会常驻运行，并根据 Android 眼镜的连接状态持续发送画面。<br>
首次运行时，macOS 会请求屏幕录制权限。

注册为登录时启动的常驻服务：

```bash
./host/scripts/install-launch-agent.sh
```

## BetterDisplay

如果 Mac 上安装了 BetterDisplay，可以从 Android 眼镜端菜单切换 Mac 端虚拟显示器的分辨率。<br>
也可以直接在 Mac 上执行相同操作。

```bash
./host/sender/glass-betterdisplay-resolution.sh 480x640
./host/sender/glass-betterdisplay-resolution.sh 480x320
./host/sender/glass-betterdisplay-resolution.sh off
```

如果没有 BetterDisplay，分辨率切换命令会失败，但屏幕串流本身仍可使用。

## 分辨率模式

Glass Display 会按照接收到的帧宽高显示串流。<br>
因此，它可以处理发送端指定的任意分辨率。<br>
当 Android 眼镜的分辨率太小无法看清时，画面会被上下分割，并在上半部分显示鼠标指针周围的放大区域。

Android 眼镜端的 Resolution 菜单用于切换 Mac 端 BetterDisplay 虚拟显示器预设。<br>
默认预设如下：

- `480x640`: 面向设备最大显示区域。
- `480x320`: 面向 split 模式，匹配上下分割显示中的一半。
- `off`: 关闭 BetterDisplay 虚拟显示器。

按 Enter 打开菜单，然后在 Resolution 中选择预设。

## 通信方式

默认优先使用 `adb`。<br>
USB 连接时使用 `adb forward tcp:19400 tcp:19400` 发送。<br>
USB 断开后，如果已同步密钥，则切换到 BLE。

固定通信方式：

```bash
./host/scripts/glass-stream.sh --transport tcp
./host/scripts/glass-stream.sh --transport ble
```

使用 BLE 前，需要先通过一次 USB 连接将加密密钥同步到 Android 眼镜。

## 加密

帧通信使用 AES-256-GCM 加密。<br>
每台 Mac 使用各自的串流密钥。通过 USB/adb 配对时，会为这台 Mac 生成新密钥，并在开始发送前同步。

Mac 端密钥保存在：

```text
~/Library/Application Support/GlassDisplay/keys
```

USB 断开后的 BLE 通信会使用最后一次同步的密钥。<br>
如果 BLE 认证失败，请用 USB 连接 Android 眼镜，然后重启服务。

```bash
./host/scripts/glass-service.sh restart
```

## 屏幕菜单

- Enter: 打开菜单 / 确认
- Right + Down: 下一项
- Left + Up: 上一项
- Resolution: BetterDisplay 预设 `480x640`、`480x320`、`off`
- Display mode: Full / Split

Split 模式下，如果存在多个 BLE 发送源，会以上下分割方式显示。

## 卸载

从 Android 眼镜删除应用：

```bash
adb uninstall bio.aq.glassdisplay
```

删除 Mac 常驻服务：

```bash
./host/scripts/glass-service.sh uninstall
```

同时删除日志和加密密钥：

```bash
rm -rf "$HOME/Library/Logs/GlassDisplay"
rm -rf "$HOME/Library/Application Support/GlassDisplay"
```

## 故障排查

检查服务状态：

```bash
./host/scripts/glass-service.sh status
./host/scripts/glass-service.sh doctor
```

查看日志：

```bash
./host/scripts/glass-service.sh logs
```

重启服务：

```bash
./host/scripts/glass-service.sh restart
```

日志文件：

- `~/Library/Logs/GlassDisplay/glass-stream.out.log`
- `~/Library/Logs/GlassDisplay/glass-stream.err.log`
