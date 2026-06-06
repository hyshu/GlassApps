#!/bin/zsh

set -euo pipefail

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  echo "Do not run this installer with sudo." >&2
  echo "LaunchAgents are installed for your logged-in user; run: ./host/scripts/install-launch-agent.sh" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
LABEL="bio.aq.glassdisplay.stream"
UID_VALUE="$(id -u)"
AGENT_DIR="$HOME/Library/LaunchAgents"
AGENT_PATH="$AGENT_DIR/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/GlassDisplay"
STDOUT_PATH="$LOG_DIR/glass-stream.out.log"
STDERR_PATH="$LOG_DIR/glass-stream.err.log"
STREAM_SCRIPT="$ROOT_DIR/host/scripts/glass-stream.sh"
SENDER_APP="$ROOT_DIR/host/sender/GlassDisplaySender.app"
SENDER_BIN="$SENDER_APP/Contents/MacOS/glass_display_sender"
TEMPLATE_PATH="$ROOT_DIR/launchd/$LABEL.plist.template"
PATH_VALUE="/usr/bin:/bin:/usr/sbin:/sbin:$HOME/Library/Android/sdk/platform-tools"

if [[ ! -x "$STREAM_SCRIPT" ]]; then
  echo "stream script missing or not executable: $STREAM_SCRIPT" >&2
  exit 1
fi

if [[ ! -f "$TEMPLATE_PATH" ]]; then
  echo "launchd template missing: $TEMPLATE_PATH" >&2
  exit 1
fi

escape_sed_replacement() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//|/\\|}"
  value="${value//&/\\&}"
  print -r -- "$value"
}

ensure_sender_app() {
  if [[ ! -x "$SENDER_BIN" ]]; then
    echo "signed sender app missing: $SENDER_APP" >&2
    echo "Restore GlassDisplaySender.app from the repository or run the Build GlassDisplaySender GitHub Actions workflow." >&2
    exit 1
  fi

  if ! codesign --verify --deep --strict "$SENDER_APP" >/dev/null 2>&1; then
    echo "signed sender app failed codesign verification: $SENDER_APP" >&2
    echo "Restore GlassDisplaySender.app from the repository or run the Build GlassDisplaySender GitHub Actions workflow." >&2
    exit 1
  fi

  echo "using sender app: $SENDER_APP"
}

mkdir -p "$AGENT_DIR" "$LOG_DIR"

if [[ ! -w "$AGENT_DIR" ]]; then
  echo "LaunchAgents directory is not writable: $AGENT_DIR" >&2
  echo "If it was created by a sudo run, repair it with:" >&2
  echo "  sudo chown -R $(id -un):staff \"$AGENT_DIR\"" >&2
  exit 1
fi

if [[ ! -w "$LOG_DIR" ]]; then
  echo "Log directory is not writable: $LOG_DIR" >&2
  echo "If it was created by a sudo run, repair it with:" >&2
  echo "  sudo chown -R $(id -un):staff \"$LOG_DIR\"" >&2
  exit 1
fi

ensure_sender_app

template_contents="$(<"$TEMPLATE_PATH")"
template_contents="${template_contents//__STREAM_SCRIPT__/$(escape_sed_replacement "$STREAM_SCRIPT")}"
template_contents="${template_contents//__WORKING_DIRECTORY__/$(escape_sed_replacement "$ROOT_DIR")}"
template_contents="${template_contents//__HOME__/$(escape_sed_replacement "$HOME")}"
template_contents="${template_contents//__PATH__/$(escape_sed_replacement "$PATH_VALUE")}"
template_contents="${template_contents//__SENDER_BIN__/$(escape_sed_replacement "$SENDER_BIN")}"
template_contents="${template_contents//__STDOUT_PATH__/$(escape_sed_replacement "$STDOUT_PATH")}"
template_contents="${template_contents//__STDERR_PATH__/$(escape_sed_replacement "$STDERR_PATH")}"

print -r -- "$template_contents" > "$AGENT_PATH"
plutil -lint "$AGENT_PATH" >/dev/null

launchctl bootout --wait "gui/$UID_VALUE/$LABEL" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$UID_VALUE" "$AGENT_PATH"
launchctl kickstart -k "gui/$UID_VALUE/$LABEL"

echo "installed: $AGENT_PATH"
echo "label: $LABEL"
echo "sender app: $SENDER_APP"
echo "sender: $SENDER_BIN"
echo "stdout: $STDOUT_PATH"
echo "stderr: $STDERR_PATH"
echo "status: $ROOT_DIR/host/scripts/glass-service.sh status"
echo "logs: $ROOT_DIR/host/scripts/glass-service.sh logs"
