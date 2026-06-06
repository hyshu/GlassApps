#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE_NAME="${GLASS_DISPLAY_PACKAGE_NAME:-bio.aq.glassdisplay}"
MAIN_ACTIVITY="${GLASS_DISPLAY_MAIN_ACTIVITY:-.MainActivity}"
PORT="${GLASS_DISPLAY_PORT:-19400}"
SENDER_APP="${GLASS_DISPLAY_SENDER_APP:-${RUNNER_TEMP:-/tmp}/GlassDisplaySender.app}"
SENDER_BIN="$SENDER_APP/Contents/MacOS/glass_display_sender"
APK_PATH="${GLASS_DISPLAY_ANDROID_APK:-app/build/outputs/apk/debug/app-debug.apk}"
KEY_HEX="${GLASS_CI_STREAM_KEY_HEX:-00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff}"
KEY_FILE="${RUNNER_TEMP:-/tmp}/glass-ci-stream.key"
DEVICE_TMP_KEY="/data/local/tmp/glass-stream.key"
DEVICE_APP_KEY="files/glass-stream.key"
LOGCAT_FILE="${RUNNER_TEMP:-/tmp}/glassdisplay-logcat.txt"
BOOT_TIMEOUT_SECONDS="${GLASS_CI_BOOT_TIMEOUT_SECONDS:-120}"
SENDER_TIMEOUT_SECONDS="${GLASS_CI_SENDER_TIMEOUT_SECONDS:-15}"

cd "$ROOT_DIR"

printf '%s\n' "$KEY_HEX" > "$KEY_FILE"

if [[ ! -f "$APK_PATH" ]]; then
  ./gradlew --no-daemon app:assembleDebug
fi

wait_for_android_ready() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  local state=""
  local boot_completed=""

  while (( SECONDS < deadline )); do
    state="$(adb get-state 2>/dev/null || true)"
    boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$state" == "device" && "$boot_completed" == "1" ]]; then
      return 0
    fi

    if [[ "$state" == "offline" ]]; then
      adb reconnect offline >/dev/null 2>&1 || true
    fi
    sleep 2
  done

  adb devices -l >&2 || true
  echo "Android emulator did not become ready: state=$state boot_completed=$boot_completed" >&2
  return 1
}

run_sender_once() {
  "$SENDER_BIN" \
    --transport tcp \
    --host 127.0.0.1 \
    --port "$PORT" \
    --key-hex "$KEY_HEX" \
    --width 32 \
    --height 32 \
    --synthetic-frames 3 &

  local sender_pid=$!
  local deadline=$((SECONDS + SENDER_TIMEOUT_SECONDS))
  while kill -0 "$sender_pid" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "sender timed out after ${SENDER_TIMEOUT_SECONDS}s" >&2
      kill "$sender_pid" >/dev/null 2>&1 || true
      sleep 1
      kill -9 "$sender_pid" >/dev/null 2>&1 || true
      wait "$sender_pid" 2>/dev/null || true
      return 124
    fi
    sleep 1
  done

  set +e
  wait "$sender_pid"
  local sender_status=$?
  set -e
  return "$sender_status"
}

adb wait-for-device
wait_for_android_ready
adb install -r "$APK_PATH" >/dev/null
adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
adb shell rm -f "$DEVICE_TMP_KEY" >/dev/null 2>&1 || true
adb push "$KEY_FILE" "$DEVICE_TMP_KEY" >/dev/null
adb shell chmod 644 "$DEVICE_TMP_KEY" >/dev/null
adb shell "run-as '$PACKAGE_NAME' sh -c 'mkdir -p files && cat \"$DEVICE_TMP_KEY\" > \"$DEVICE_APP_KEY\" && chmod 600 \"$DEVICE_APP_KEY\"'"
adb shell rm -f "$DEVICE_TMP_KEY" >/dev/null 2>&1 || true

adb forward --remove "tcp:$PORT" >/dev/null 2>&1 || true
adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null
adb logcat -c
adb shell am start -W -n "$PACKAGE_NAME/$MAIN_ACTIVITY" >/dev/null

if [[ ! -x "$SENDER_BIN" ]]; then
  echo "sender binary missing: $SENDER_BIN" >&2
  exit 1
fi

connected=0
for _ in {1..30}; do
  if run_sender_once; then
    connected=1
    break
  fi
  sleep 1
done

if [[ "$connected" != "1" ]]; then
  adb logcat -d > "$LOGCAT_FILE" || true
  echo "sender did not complete tcp frame exchange" >&2
  exit 1
fi

adb logcat -d > "$LOGCAT_FILE"
if ! grep -q "GlassFrameServer.*Client connected" "$LOGCAT_FILE"; then
  echo "Android app did not log TCP client connection" >&2
  exit 1
fi

echo "macOS sender exchanged TCP frames with Android app"
