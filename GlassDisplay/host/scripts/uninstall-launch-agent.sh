#!/bin/zsh

set -euo pipefail

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  echo "Do not run this uninstaller with sudo." >&2
  echo "LaunchAgents are installed for your logged-in user; run: ./host/scripts/uninstall-launch-agent.sh" >&2
  exit 1
fi

LABEL="bio.aq.glassdisplay.stream"
UID_VALUE="$(id -u)"
AGENT_PATH="$HOME/Library/LaunchAgents/$LABEL.plist"

if [[ -d "$HOME/Library/LaunchAgents" && ! -w "$HOME/Library/LaunchAgents" ]]; then
  echo "LaunchAgents directory is not writable: $HOME/Library/LaunchAgents" >&2
  echo "If it was created by a sudo run, repair it with:" >&2
  echo "  sudo chown -R $(id -un):staff \"$HOME/Library/LaunchAgents\"" >&2
  exit 1
fi

launchctl bootout --wait "gui/$UID_VALUE/$LABEL" >/dev/null 2>&1 || true

if [[ -f "$AGENT_PATH" ]]; then
  rm -f "$AGENT_PATH"
  echo "removed: $AGENT_PATH"
else
  echo "not installed: $AGENT_PATH"
fi
