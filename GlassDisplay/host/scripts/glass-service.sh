#!/bin/zsh

set -euo pipefail

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  echo "Do not run this service helper with sudo." >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
LABEL="bio.aq.glassdisplay.stream"
UID_VALUE="$(id -u)"
DOMAIN="gui/$UID_VALUE"
SERVICE="$DOMAIN/$LABEL"
AGENT_PATH="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/GlassDisplay"
STDOUT_PATH="$LOG_DIR/glass-stream.out.log"
STDERR_PATH="$LOG_DIR/glass-stream.err.log"
SENDER_APP="$ROOT_DIR/host/sender/GlassDisplaySender.app"
SENDER_BIN="$SENDER_APP/Contents/MacOS/glass_display_sender"
INSTALL_SCRIPT="$ROOT_DIR/host/scripts/install-launch-agent.sh"
UNINSTALL_SCRIPT="$ROOT_DIR/host/scripts/uninstall-launch-agent.sh"
ADB_BIN="${ADB_BIN:-$HOME/Library/Android/sdk/platform-tools/adb}"

usage() {
  cat <<EOF
Usage: host/scripts/glass-service.sh <command>

Commands:
  status        Show LaunchAgent state and recent failure hints
  doctor        Check common setup, permission, and runtime problems
  logs          Print the latest logs
  logs -f       Follow logs
  restart       Reload the installed LaunchAgent without rebuilding
  start         Load and start the installed LaunchAgent
  stop          Stop the LaunchAgent
  install       Install/update the LaunchAgent using the bundled sender app
  uninstall     Stop and remove the LaunchAgent
  permissions   Open Screen Recording settings
EOF
}

loaded() {
  launchctl print "$SERVICE" >/dev/null 2>&1
}

print_service_summary() {
  if ! loaded; then
    echo "loaded: no"
    return
  fi

  echo "loaded: yes"
  launchctl print "$SERVICE" 2>/dev/null | awk '
    /^\tstate = / && !seen_state { print; seen_state = 1; next }
    /^\truns = / && !seen_runs { print; seen_runs = 1; next }
    /^\tpid = / && !seen_pid { print; seen_pid = 1; next }
    /^\tlast exit code = / && !seen_exit { print; seen_exit = 1; next }
    /^\tlast terminating signal = / && !seen_signal { print; seen_signal = 1; next }
  '
}

print_paths() {
  echo "plist: $AGENT_PATH"
  echo "stdout: $STDOUT_PATH"
  echo "stderr: $STDERR_PATH"
  echo "sender: $SENDER_BIN"
}

recent_error_log() {
  if [[ -f "$STDERR_PATH" ]]; then
    tail -n 80 "$STDERR_PATH"
  fi
}

print_hints() {
  local recent
  local latest
  recent="$(recent_error_log 2>/dev/null || true)"
  latest="$(
    print -r -- "$recent" | awk '
      /SCStreamErrorDomain Code=-3801|TCC|Encrypted frame authentication failed|Bluetooth permission denied|no adb device|stream key unavailable|no stream key for BLE|adb device ready|sender active via tcp|connected via tcp|sender active via ble/ {
        line = $0
      }
      END { print line }
    '
  )"

  if [[ "$latest" == *"SCStreamErrorDomain Code=-3801"* || "$latest" == *"TCC"* ]]; then
    echo
    echo "hint: Screen Recording permission is denied for GlassDisplaySender.app."
    echo "      Run: ./host/scripts/glass-service.sh permissions"
  fi

  if [[ "$latest" == *"no adb device"* ]]; then
    echo
    echo "hint: no adb device is connected; BLE fallback needs a key installed once over adb."
  fi

  if [[ "$latest" == *"stream key unavailable"* || "$latest" == *"no stream key for BLE"* ]]; then
    echo
    echo "hint: connect the device over adb once so the stream key can be installed."
  fi

  if [[ "$latest" == *"Encrypted frame authentication failed"* ]]; then
    echo
    echo "hint: BLE encryption failed, usually because the stream keys differ."
    echo "      Connect the device over adb, then run: ./host/scripts/glass-service.sh restart"
  fi

  if [[ "$latest" == *"Bluetooth permission denied"* ]]; then
    echo
    echo "hint: Bluetooth permission is denied for GlassDisplaySender.app."
    echo "      Open System Settings > Privacy & Security > Bluetooth and allow GlassDisplaySender."
  fi
}

status() {
  print_service_summary
  print_paths
  print_hints
}

check_writable_dir() {
  local path="$1"
  local label="$2"

  if [[ ! -d "$path" ]]; then
    echo "WARN: $label does not exist yet: $path"
    return
  fi

  if [[ -w "$path" ]]; then
    echo "OK: $label is writable"
    return
  fi

  echo "FAIL: $label is not writable: $path"
  echo "      repair: sudo chown -R $(id -un):staff \"$path\""
}

doctor() {
  check_writable_dir "$HOME/Library/LaunchAgents" "LaunchAgents directory"
  check_writable_dir "$LOG_DIR" "Log directory"

  if [[ -f "$AGENT_PATH" ]]; then
    if plutil -lint "$AGENT_PATH" >/dev/null; then
      echo "OK: plist is valid"
    else
      echo "FAIL: plist is invalid: $AGENT_PATH"
    fi
  else
    echo "WARN: LaunchAgent is not installed"
    echo "      install: ./host/scripts/glass-service.sh install"
  fi

  if [[ -x "$SENDER_BIN" ]]; then
    echo "OK: sender is executable"
  else
    echo "WARN: sender is missing or not executable"
    echo "      restore GlassDisplaySender.app from the repository or run the Build GlassDisplaySender workflow"
  fi

  if [[ -x "$ADB_BIN" || -n "$(command -v adb || true)" ]]; then
    echo "OK: adb is available"
  else
    echo "WARN: adb not found; TCP setup and initial BLE key install will not work"
  fi

  print_service_summary
  print_hints
}

start_service() {
  if loaded; then
    launchctl kickstart -k "$SERVICE"
    echo "started: $LABEL"
    return
  fi

  if [[ ! -f "$AGENT_PATH" ]]; then
    echo "not installed: $AGENT_PATH" >&2
    echo "run: ./host/scripts/glass-service.sh install" >&2
    exit 1
  fi

  launchctl bootstrap "$DOMAIN" "$AGENT_PATH"
  launchctl kickstart -k "$SERVICE"
  echo "started: $LABEL"
}

stop_service() {
  launchctl bootout --wait "$SERVICE" >/dev/null 2>&1 || true
  echo "stopped: $LABEL"
}

restart_service() {
  if [[ ! -f "$AGENT_PATH" ]]; then
    echo "not installed: $AGENT_PATH" >&2
    echo "run: ./host/scripts/glass-service.sh install" >&2
    exit 1
  fi

  launchctl bootout --wait "$SERVICE" >/dev/null 2>&1 || true
  launchctl bootstrap "$DOMAIN" "$AGENT_PATH"
  launchctl kickstart -k "$SERVICE"
  echo "restarted: $LABEL"
}

show_logs() {
  local -a tail_args
  tail_args=(-n 120)

  if [[ "${1:-}" == "-f" ]]; then
    tail_args=(-f)
  elif [[ "${1:-}" == "-n" && "${2:-}" == <-> ]]; then
    tail_args=(-n "$2")
  fi

  mkdir -p "$LOG_DIR"
  touch "$STDOUT_PATH" "$STDERR_PATH"
  tail "${tail_args[@]}" "$STDOUT_PATH" "$STDERR_PATH"
}

open_permissions() {
  open "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
  echo "Opened Screen Recording settings."
  echo "Grant permission to: $SENDER_APP"
}

command_name="${1:-status}"
if (( $# > 0 )); then
  shift
fi

case "$command_name" in
  status)
    status
    ;;
  doctor)
    doctor
    ;;
  logs)
    show_logs "$@"
    ;;
  restart)
    restart_service
    ;;
  start)
    start_service
    ;;
  stop)
    stop_service
    ;;
  install)
    "$INSTALL_SCRIPT"
    ;;
  uninstall)
    "$UNINSTALL_SCRIPT"
    ;;
  permissions)
    open_permissions
    ;;
  help|--help|-h)
    usage
    ;;
  *)
    echo "unknown command: $command_name" >&2
    usage >&2
    exit 1
    ;;
esac
