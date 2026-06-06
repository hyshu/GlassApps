#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SENDER_SOURCE="$ROOT_DIR/glass_display_sender.swift"
SENDER_IDENTIFIER="${GLASS_DISPLAY_SENDER_IDENTIFIER:-bio.aq.glassdisplay.glassdisplay-sender}"
SENDER_APP="${GLASS_DISPLAY_SENDER_APP:-$ROOT_DIR/GlassDisplaySender.app}"
SENDER_CODESIGN_IDENTITY="${GLASS_DISPLAY_CODESIGN_IDENTITY:--}"
SENDER_CODESIGN_ENTITLEMENTS="${GLASS_DISPLAY_CODESIGN_ENTITLEMENTS:-}"
SENDER_CODESIGN_TIMESTAMP="${GLASS_DISPLAY_CODESIGN_TIMESTAMP:-0}"
SENDER_CODESIGN_HARDENED_RUNTIME="${GLASS_DISPLAY_CODESIGN_HARDENED_RUNTIME:-0}"
SENDER_APP_CONTENTS="$SENDER_APP/Contents"
SENDER_APP_MACOS="$SENDER_APP_CONTENTS/MacOS"
SENDER_BIN="$SENDER_APP_MACOS/glass_display_sender"
SENDER_INFO_TEMPLATE="$ROOT_DIR/Info.plist.template"
SENDER_APP_INFO="$SENDER_APP_CONTENTS/Info.plist"
LSREGISTER_BIN="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
force=0

usage() {
  cat <<EOF
Usage: host/sender/build.sh [--force]

Builds host/sender/GlassDisplaySender.app and signs it with the stable sender identifier.
Set GLASS_DISPLAY_CODESIGN_IDENTITY to a certificate name or SHA-1 hash for a real signature.
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --force)
      force=1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ ! -f "$SENDER_SOURCE" ]]; then
  echo "sender source missing: $SENDER_SOURCE" >&2
  exit 1
fi

if [[ ! -f "$SENDER_INFO_TEMPLATE" ]]; then
  echo "sender Info.plist template missing: $SENDER_INFO_TEMPLATE" >&2
  exit 1
fi

escape_sed_replacement() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//|/\\|}"
  value="${value//&/\\&}"
  print -r -- "$value"
}

mkdir -p "$SENDER_APP_MACOS"

build_needed=0
if (( force )) || [[ ! -x "$SENDER_BIN" || "$SENDER_SOURCE" -nt "$SENDER_BIN" ]]; then
  build_needed=1
fi

if (( build_needed )); then
  swiftc -o "$SENDER_BIN" "$SENDER_SOURCE"
fi

sender_info_contents="$(<"$SENDER_INFO_TEMPLATE")"
sender_info_contents="${sender_info_contents//__SENDER_IDENTIFIER__/$(escape_sed_replacement "$SENDER_IDENTIFIER")}"

info_needed=0
if [[ ! -f "$SENDER_APP_INFO" ]] || [[ "$(<"$SENDER_APP_INFO")" != "$sender_info_contents" ]]; then
  info_needed=1
  print -r -- "$sender_info_contents" > "$SENDER_APP_INFO"
fi

codesign_args=(
  --force \
  --sign "$SENDER_CODESIGN_IDENTITY" \
  --identifier "$SENDER_IDENTIFIER" \
  --requirements "=designated => identifier \"$SENDER_IDENTIFIER\""
)

if [[ -n "$SENDER_CODESIGN_ENTITLEMENTS" ]]; then
  codesign_args+=(--entitlements "$SENDER_CODESIGN_ENTITLEMENTS")
fi

if [[ "$SENDER_CODESIGN_IDENTITY" != "-" ]]; then
  if [[ "$SENDER_CODESIGN_TIMESTAMP" == "1" ]]; then
    codesign_args+=(--timestamp)
  fi
  if [[ "$SENDER_CODESIGN_HARDENED_RUNTIME" == "1" ]]; then
    codesign_args+=(--options runtime)
  fi
fi

sign_needed=0
if (( force || build_needed || info_needed )) || [[ -n "${GLASS_DISPLAY_CODESIGN_IDENTITY+x}" ]]; then
  sign_needed=1
elif ! codesign --verify --deep --strict "$SENDER_APP" >/dev/null 2>&1; then
  sign_needed=1
fi

if (( sign_needed )); then
  codesign "${codesign_args[@]}" "$SENDER_APP"
fi

if [[ -x "$LSREGISTER_BIN" ]]; then
  "$LSREGISTER_BIN" -f "$SENDER_APP" >/dev/null 2>&1 || true
fi

echo "app: $SENDER_APP"
echo "sender: $SENDER_BIN"
if [[ "$SENDER_CODESIGN_IDENTITY" == "-" ]]; then
  if (( sign_needed )); then
    echo "codesign: ad-hoc"
  else
    echo "codesign: unchanged"
  fi
else
  echo "codesign: certificate"
fi
