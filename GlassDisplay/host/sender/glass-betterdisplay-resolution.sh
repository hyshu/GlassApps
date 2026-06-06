#!/bin/zsh

set -uo pipefail

BETTERDISPLAY_BIN="${BETTERDISPLAY_BIN:-/Applications/BetterDisplay.app/Contents/MacOS/BetterDisplay}"
BD_TIMEOUT_SECONDS="${GLASS_BETTERDISPLAY_TIMEOUT_SECONDS:-8}"
PORTRAIT_NAME_LIKE="${GLASS_BETTERDISPLAY_PORTRAIT_NAME_LIKE:-GlassDisplay 480x640}"
LANDSCAPE_NAME_LIKE="${GLASS_BETTERDISPLAY_LANDSCAPE_NAME_LIKE:-GlassDisplay 480x320}"
LEGACY_PORTRAIT_NAME_LIKE="${GLASS_BETTERDISPLAY_LEGACY_PORTRAIT_NAME_LIKE:-3:4}"
FALLBACK_DISPLAY_ID="${GLASS_BETTERDISPLAY_FALLBACK_DISPLAY_ID:-1}"
FALLBACK_TAG_ID="${GLASS_BETTERDISPLAY_FALLBACK_TAG_ID:-2}"

usage() {
  echo "Usage: host/sender/glass-betterdisplay-resolution.sh 480x640|480x320|off"
  echo "Switches or disables the BetterDisplay virtual screen used by GlassDisplay."
}

if (( $# != 1 )) || [[ "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  exit $(( $# == 1 ? 0 : 1 ))
fi

TARGET_RESOLUTION="$1"

if [[ ! -x "$BETTERDISPLAY_BIN" ]]; then
  echo "BetterDisplay CLI not found: $BETTERDISPLAY_BIN" >&2
  exit 1
fi

bd() {
  local output_file error_file pid watchdog exit_code

  output_file="$(mktemp -t glass-bd-out.XXXXXX)"
  error_file="$(mktemp -t glass-bd-err.XXXXXX)"

  "$BETTERDISPLAY_BIN" "$@" >"$output_file" 2>"$error_file" &
  pid=$!

  (
    sleep "$BD_TIMEOUT_SECONDS"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -9 "$pid" 2>/dev/null || true
    fi
  ) &
  watchdog=$!

  wait "$pid"
  exit_code=$?

  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true

  cat "$output_file"
  cat "$error_file" >&2
  rm -f "$output_file" "$error_file"

  if (( exit_code == 137 || exit_code == 143 )); then
    echo "BetterDisplay CLI timed out: $*" >&2
    return 124
  fi

  return "$exit_code"
}

display_tag_id_for_name_like() {
  local name_like="$1"
  bd get -nameLike="$name_like" -identifiers 2>/dev/null \
    | awk -F'"' '/tagID [(]Display[)]/ { print $4; exit }' \
    || true
}

display_id_for_name_like() {
  local name_like="$1"
  bd get -nameLike="$name_like" -identifiers 2>/dev/null \
    | awk -F'"' '/"displayID"/ { print $4; exit }' \
    || true
}

disconnect_virtual_screen() {
  local name_like="$1"
  local display_tag_id

  display_tag_id="$(display_tag_id_for_name_like "$name_like")"
  bd set -nameLike="$name_like" -connected=off >/dev/null 2>&1 || true

  if [[ -n "$display_tag_id" ]]; then
    bd set -tagID="$display_tag_id" -connected=off >/dev/null 2>&1 || true
  fi
}

discard_virtual_screen() {
  local name_like="$1"
  bd discard -nameLike="$name_like" >/dev/null 2>&1 || true
}

set_fallback_main() {
  caffeinate -u -t 2 >/dev/null 2>&1 || true

  bd set -displayID="$FALLBACK_DISPLAY_ID" -main=on >/dev/null 2>&1 \
    || bd set -tagID="$FALLBACK_TAG_ID" -main=on >/dev/null 2>&1 \
    || true

  sleep 2

  if [[ "$(bd get -displayID="$FALLBACK_DISPLAY_ID" -main 2>/dev/null || true)" != "true" ]]; then
    bd set -tagID="$FALLBACK_TAG_ID" -main=on >/dev/null 2>&1 || true
    sleep 1
  fi
}

set_virtual_main() {
  local name_like="$1"
  local display_id

  bd set -nameLike="$name_like" -main=on >/dev/null 2>&1 || true

  display_id="$(display_id_for_name_like "$name_like")"
  if [[ -n "$display_id" && "$display_id" != "0" ]]; then
    bd set -displayID="$display_id" -main=on >/dev/null 2>&1 || true
  fi

  sleep 1
}

ensure_landscape_screen() {
  if ! bd get -nameLike="$LANDSCAPE_NAME_LIKE" -identifiers >/dev/null 2>&1; then
    bd create \
      -type=VirtualScreen \
      -virtualScreenName="$LANDSCAPE_NAME_LIKE" \
      -aspectWidth=3 \
      -aspectHeight=2 \
      -useResolutionList=on \
      -resolutionList=480x320 \
      -virtualScreenHiDPI=off \
      -connected=on \
      >/dev/null 2>&1 \
      || true
  fi

  bd set \
    -nameLike="$LANDSCAPE_NAME_LIKE" \
    -useResolutionList=on \
    -resolutionList=480x320 \
    -virtualScreenHiDPI=off \
    >/dev/null 2>&1 \
    || true
}

ensure_portrait_screen() {
  if ! bd get -nameLike="$PORTRAIT_NAME_LIKE" -identifiers >/dev/null 2>&1; then
    bd create \
      -type=VirtualScreen \
      -virtualScreenName="$PORTRAIT_NAME_LIKE" \
      -aspectWidth=3 \
      -aspectHeight=4 \
      -useResolutionList=on \
      -resolutionList=480x640 \
      -virtualScreenHiDPI=off \
      -connected=on \
      >/dev/null 2>&1 \
      || true
  fi

  bd set \
    -nameLike="$PORTRAIT_NAME_LIKE" \
    -useResolutionList=on \
    -resolutionList=480x640 \
    -virtualScreenHiDPI=off \
    >/dev/null 2>&1 \
    || true
}

discard_glassdisplay_screens() {
  discard_virtual_screen "$LANDSCAPE_NAME_LIKE"
  discard_virtual_screen "$PORTRAIT_NAME_LIKE"
  discard_virtual_screen "$LEGACY_PORTRAIT_NAME_LIKE"
  sleep 1
}

switch_to_portrait() {
  set_fallback_main
  discard_glassdisplay_screens
  ensure_portrait_screen

  bd set -nameLike="$PORTRAIT_NAME_LIKE" -connected=on >/dev/null 2>&1 || true
  sleep 2
  set_virtual_main "$PORTRAIT_NAME_LIKE"
}

switch_to_landscape() {
  set_fallback_main
  discard_glassdisplay_screens
  ensure_landscape_screen

  bd set -nameLike="$LANDSCAPE_NAME_LIKE" -connected=on >/dev/null 2>&1 || true
  sleep 2
  set_virtual_main "$LANDSCAPE_NAME_LIKE"
}

switch_off() {
  set_fallback_main
  sleep 1

  discard_glassdisplay_screens

  set_fallback_main
}

case "$TARGET_RESOLUTION" in
  480x640)
    switch_to_portrait
    ;;
  480x320)
    switch_to_landscape
    ;;
  off|virtual-off)
    switch_off
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac

if [[ "$TARGET_RESOLUTION" == "off" || "$TARGET_RESOLUTION" == "virtual-off" ]]; then
  echo "virtual display: off"
else
  current_resolution="$(bd get -displayWithMainStatus -resolution 2>/dev/null || true)"
  if [[ -n "$current_resolution" ]]; then
    echo "main resolution: $current_resolution"
  else
    echo "requested resolution: $TARGET_RESOLUTION"
  fi
fi
