#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ADB_BIN="${ADB_BIN:-$HOME/Library/Android/sdk/platform-tools/adb}"
CHECK_INTERVAL="${GLASS_STREAM_CHECK_INTERVAL:-2}"
FAST_FAILURE_DELAY="${GLASS_STREAM_FAST_FAILURE_DELAY:-15}"
PACKAGE_NAME="bio.aq.glassdisplay"
DEVICE_TMP_KEY_PATH="/data/local/tmp/glassdisplay-stream.key"
DEVICE_TMP_ID_PATH="/data/local/tmp/glassdisplay-device-id"
DEVICE_TMP_HOST_ID_PATH="/data/local/tmp/glassdisplay-host-id"
DEVICE_APP_KEY_PATH="files/glass-stream.key"
DEVICE_APP_ID_PATH="files/glass-device-id"
DEVICE_APP_HOST_ID_PATH="files/glass-host-id"
HOST_KEY_DIR="$HOME/Library/Application Support/GlassDisplay/keys"
HOST_ID_FILE="${GLASS_STREAM_HOST_ID_FILE:-$HOST_KEY_DIR/host.id}"
PORT=19400
sender_args=()
sender_pid=""
sender_started_at=0
selected_device=""
sender_device=""
sender_transport=""
sender_key_file=""
sender_cmd=()
sender_description=""
stream_key_device=""
stream_key_file="${GLASS_STREAM_KEY_FILE:-}"
force_transport="${GLASS_STREAM_TRANSPORT:-}"
tcp_backoff_until=0

if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if (( $# > 0 )) && [[ "$1" == "--help" || "$1" == "-h" ]]; then
  echo "Usage: host/scripts/glass-stream.sh [--transport tcp|ble] [--port 19400] [sender options]"
  echo "Uses adb TCP when available, then falls back to BLE with the last installed stream key."
  echo "Creates and syncs a fresh AES-256-GCM stream key during adb setup."
  exit 0
fi

if [[ -n "$force_transport" && "$force_transport" != "tcp" && "$force_transport" != "ble" ]]; then
  echo "Invalid GLASS_STREAM_TRANSPORT (expected tcp|ble)." >&2
  exit 1
fi

adb_available() {
  [[ -n "${ADB_BIN:-}" && -x "$ADB_BIN" ]]
}

resolve_sender_command() {
  if [[ -n "${GLASS_DISPLAY_SENDER_BIN:-}" ]]; then
    if [[ ! -x "${GLASS_DISPLAY_SENDER_BIN}" ]]; then
      echo "GLASS_DISPLAY_SENDER_BIN is not executable: ${GLASS_DISPLAY_SENDER_BIN}" >&2
      exit 1
    fi

    sender_cmd=("${GLASS_DISPLAY_SENDER_BIN}")
    sender_description="${GLASS_DISPLAY_SENDER_BIN}"
    return
  fi

  local sender_dir="$ROOT_DIR/host/sender"
  local bundled_app="$sender_dir/GlassDisplaySender.app"
  local bundled_sender="$sender_dir/GlassDisplaySender.app/Contents/MacOS/glass_display_sender"

  if [[ -x "$bundled_sender" ]]; then
    if ! codesign --verify --deep --strict "$bundled_app" >/dev/null 2>&1; then
      echo "signed sender app failed codesign verification: $bundled_app" >&2
      echo "Restore GlassDisplaySender.app from the repository or run the Build GlassDisplaySender GitHub Actions workflow." >&2
      exit 1
    fi

    sender_cmd=("$bundled_sender")
    sender_description="$bundled_sender"
    return
  fi

  echo "signed sender app missing: $bundled_app" >&2
  echo "Restore GlassDisplaySender.app from the repository or run the Build GlassDisplaySender GitHub Actions workflow." >&2
  exit 1
}

while (( $# > 0 )); do
  case "$1" in
    --port)
      shift
      if (( $# == 0 )) || [[ "$1" != <-> ]] || (( $1 <= 0 )); then
        echo "Invalid value for --port." >&2
        exit 1
      fi
      PORT="$1"
      sender_args+=(--port "$1")
      ;;
    --transport)
      shift
      if (( $# == 0 )) || [[ "$1" != "tcp" && "$1" != "ble" ]]; then
        echo "Invalid value for --transport (expected tcp|ble)." >&2
        exit 1
      fi
      force_transport="$1"
      ;;
    *)
      sender_args+=("$1")
      ;;
  esac
  shift
done

log_state() {
  local key="$1"
  local next_state="$2"
  local message="$3"
  local current="${(P)key:-}"
  if [[ "$current" != "$next_state" ]]; then
    typeset -g "$key=$next_state"
    print -u2 -- "$message"
  fi
}

log_device_state() {
  log_state DEVICE_STATE "$1" "$2"
}

log_sender_state() {
  log_state SENDER_STATE "$1" "$2"
}

resolve_serial_args() {
  serial_args=()
  selected_device=""

  if ! adb_available; then
    log_device_state "adb-missing" "adb not found; BLE fallback only"
    return 1
  fi

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    if "$ADB_BIN" -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1; then
      serial_args=(-s "$ANDROID_SERIAL")
      selected_device="$ANDROID_SERIAL"
      log_device_state "device:$selected_device" "adb device ready: $selected_device"
      return 0
    fi

    log_device_state "waiting:$ANDROID_SERIAL" "waiting for adb device: $ANDROID_SERIAL"
    return 1
  fi

  local devices_output
  devices_output="$("$ADB_BIN" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')"
  local -a devices
  if [[ -n "$devices_output" ]]; then
    devices=("${(@f)devices_output}")
  else
    devices=()
  fi

  if (( ${#devices[@]} == 0 )); then
    log_device_state "waiting" "no adb device; will fall back to BLE"
    return 1
  fi

  if (( ${#devices[@]} > 1 )); then
    log_device_state "multiple" "multiple adb devices found; set ANDROID_SERIAL"
    return 1
  fi

  selected_device="${devices[1]}"
  serial_args=(-s "$selected_device")
  log_device_state "device:$selected_device" "adb device ready: $selected_device"
  return 0
}

sender_running() {
  [[ -n "$sender_pid" ]] && kill -0 "$sender_pid" 2>/dev/null
}

stop_sender() {
  local reason="$1"

  if sender_running; then
    log_sender_state "stopping:$reason" "sender stopped: $reason"
    kill "$sender_pid" 2>/dev/null || true
    wait "$sender_pid" 2>/dev/null || true
  fi

  sender_pid=""
  sender_started_at=0
  sender_device=""
  sender_transport=""
  sender_key_file=""
}

start_sender() {
  local transport="$1"
  local label

  if [[ -z "$stream_key_file" || ! -f "$stream_key_file" ]]; then
    log_sender_state "waiting-key-file" "stream key unavailable; connect over adb once"
    return 1
  fi

  if [[ "$transport" == "tcp" ]]; then
    label="$selected_device tcp:$PORT"
    "${sender_cmd[@]}" "${sender_args[@]}" --transport tcp --key-file "$stream_key_file" &
  else
    local id_file
    local id_hex
    local host_id_hex
    local -a ble_identity_args

    id_file="$(device_id_file_for_key "$stream_key_file")"
    ble_identity_args=()
    if ! is_valid_host_id_file "$HOST_ID_FILE"; then
      log_sender_state "waiting-host-id" "BLE host id unavailable; connect over adb once"
      return 1
    fi
    host_id_hex="$(tr -d '[:space:]' < "$HOST_ID_FILE")"
    ble_identity_args=(--ble-host-id-hex "$host_id_hex")
    if is_valid_device_id_file "$id_file"; then
      id_hex="$(tr -d '[:space:]' < "$id_file")"
      ble_identity_args+=(--ble-device-id-hex "$id_hex")
      label="ble:$id_hex"
    else
      label="ble"
    fi
    "${sender_cmd[@]}" "${sender_args[@]}" --transport ble --key-file "$stream_key_file" "${ble_identity_args[@]}" &
  fi

  sender_pid=$!
  sender_started_at=$SECONDS
  sender_device="$selected_device"
  sender_transport="$transport"
  sender_key_file="$stream_key_file"
  log_sender_state "running:$transport:$label:$stream_key_file:$sender_description" "sender active via $transport ($label) using $sender_description"
}

safe_device_name() {
  printf '%s' "$1" | LC_ALL=C tr -c 'A-Za-z0-9._-' '_'
}

generate_stream_key() {
  LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}

generate_device_id() {
  LC_ALL=C od -An -N8 -tx1 /dev/urandom | tr -d ' \n'
}

is_valid_stream_key_file() {
  local key_file="$1"

  [[ -f "$key_file" ]] || return 1
  is_valid_stream_key_hex "$(tr -d '[:space:]' < "$key_file")"
}

is_valid_device_id_file() {
  local id_file="$1"

  [[ -f "$id_file" ]] || return 1
  is_valid_device_id_hex "$(tr -d '[:space:]' < "$id_file")"
}

is_valid_host_id_file() {
  local id_file="$1"

  [[ -f "$id_file" ]] || return 1
  is_valid_device_id_hex "$(tr -d '[:space:]' < "$id_file")"
}

device_id_file_for_key() {
  local key_file="$1"
  print -r -- "${key_file%.key}.id"
}

is_valid_stream_key_hex() {
  local key_hex="$1"
  [[ "$key_hex" =~ '^[0-9A-Fa-f]{64}$' ]]
}

is_valid_device_id_hex() {
  local id_hex="$1"
  [[ "$id_hex" =~ '^[0-9A-Fa-f]{16}$' ]]
}

write_hex_file() {
  local hex="$1"
  local output_file="$2"

  print -r -- "$hex" > "$output_file"
  chmod 600 "$output_file"
}

read_device_app_file() {
  local app_path="$1"
  "$ADB_BIN" "${serial_args[@]}" shell "run-as '$PACKAGE_NAME' cat '$app_path' 2>/dev/null" 2>/dev/null | tr -d '\r[:space:]'
}

stream_key_fingerprint() {
  local key_file="$1"
  shasum -a 256 "$key_file" 2>/dev/null | awk '{ print substr($1, 1, 12) }'
}

write_pending_host_stream_key() {
  local output_file="$1"

  mkdir -p "$HOST_KEY_DIR"
  umask 077

  write_hex_file "$(generate_stream_key)" "$output_file"
}

ensure_host_id() {
  mkdir -p "$HOST_KEY_DIR"
  mkdir -p "$(dirname "$HOST_ID_FILE")"
  umask 077

  if is_valid_host_id_file "$HOST_ID_FILE"; then
    return 0
  fi

  write_hex_file "$(generate_device_id)" "$HOST_ID_FILE"
}

write_pending_host_device_id() {
  local id_file="$1"
  local output_file="$2"
  local remote_id_hex

  mkdir -p "$HOST_KEY_DIR"
  umask 077

  remote_id_hex="$(read_device_app_file "$DEVICE_APP_ID_PATH" || true)"
  if is_valid_device_id_hex "$remote_id_hex"; then
    write_hex_file "$remote_id_hex" "$output_file"
    return
  fi

  if is_valid_device_id_file "$id_file"; then
    cp "$id_file" "$output_file"
    chmod 600 "$output_file"
    return
  fi

  write_hex_file "$(generate_device_id)" "$output_file"
}

install_stream_key() {
  local safe_device
  local key_file
  local id_file
  local pending_key_file
  local pending_id_file
  local host_id_file
  local fingerprint

  safe_device="$(safe_device_name "$selected_device")"
  key_file="$HOST_KEY_DIR/$safe_device.key"
  id_file="$(device_id_file_for_key "$key_file")"
  pending_key_file="$key_file.pending"
  pending_id_file="$id_file.pending"
  host_id_file="$HOST_ID_FILE"
  ensure_host_id
  write_pending_host_stream_key "$pending_key_file"
  write_pending_host_device_id "$id_file" "$pending_id_file"
  fingerprint="$(stream_key_fingerprint "$pending_key_file")"

  if ! "$ADB_BIN" "${serial_args[@]}" push "$pending_key_file" "$DEVICE_TMP_KEY_PATH" >/dev/null 2>&1; then
    rm -f "$pending_key_file" "$pending_id_file"
    log_sender_state "waiting-key-push:$selected_device" "waiting for adb stream key upload on $selected_device"
    return 1
  fi

  if ! "$ADB_BIN" "${serial_args[@]}" push "$pending_id_file" "$DEVICE_TMP_ID_PATH" >/dev/null 2>&1; then
    "$ADB_BIN" "${serial_args[@]}" shell "rm -f '$DEVICE_TMP_KEY_PATH' '$DEVICE_TMP_ID_PATH' '$DEVICE_TMP_HOST_ID_PATH'" >/dev/null 2>&1 || true
    rm -f "$pending_key_file" "$pending_id_file"
    log_sender_state "waiting-id-push:$selected_device" "waiting for adb device id upload on $selected_device"
    return 1
  fi

  if ! "$ADB_BIN" "${serial_args[@]}" push "$host_id_file" "$DEVICE_TMP_HOST_ID_PATH" >/dev/null 2>&1; then
    "$ADB_BIN" "${serial_args[@]}" shell "rm -f '$DEVICE_TMP_KEY_PATH' '$DEVICE_TMP_ID_PATH' '$DEVICE_TMP_HOST_ID_PATH'" >/dev/null 2>&1 || true
    rm -f "$pending_key_file" "$pending_id_file"
    log_sender_state "waiting-host-id-push:$selected_device" "waiting for adb host id upload on $selected_device"
    return 1
  fi

  if ! "$ADB_BIN" "${serial_args[@]}" shell "run-as '$PACKAGE_NAME' sh -c 'cat \"$DEVICE_TMP_KEY_PATH\" > \"$DEVICE_APP_KEY_PATH\" && chmod 600 \"$DEVICE_APP_KEY_PATH\" && cat \"$DEVICE_TMP_ID_PATH\" > \"$DEVICE_APP_ID_PATH\" && chmod 600 \"$DEVICE_APP_ID_PATH\" && cat \"$DEVICE_TMP_HOST_ID_PATH\" > \"$DEVICE_APP_HOST_ID_PATH\" && chmod 600 \"$DEVICE_APP_HOST_ID_PATH\"'" >/dev/null 2>&1; then
    "$ADB_BIN" "${serial_args[@]}" shell "rm -f '$DEVICE_TMP_KEY_PATH' '$DEVICE_TMP_ID_PATH' '$DEVICE_TMP_HOST_ID_PATH'" >/dev/null 2>&1 || true
    rm -f "$pending_key_file" "$pending_id_file"
    log_sender_state "waiting-key-install:$selected_device" "waiting for adb stream key install on $selected_device"
    return 1
  fi

  "$ADB_BIN" "${serial_args[@]}" shell "rm -f '$DEVICE_TMP_KEY_PATH' '$DEVICE_TMP_ID_PATH' '$DEVICE_TMP_HOST_ID_PATH'" >/dev/null 2>&1 || true
  mv "$pending_key_file" "$key_file"
  mv "$pending_id_file" "$id_file"
  stream_key_device="$selected_device"
  stream_key_file="$key_file"
  log_sender_state "key-installed:$selected_device:$fingerprint" "new stream key synced for $selected_device ($fingerprint)"
  return 0
}

find_existing_stream_key() {
  if [[ -n "${GLASS_STREAM_KEY_FILE:-}" ]]; then
    if [[ -f "${GLASS_STREAM_KEY_FILE}" ]]; then
      stream_key_file="${GLASS_STREAM_KEY_FILE}"
      return 0
    fi
    log_sender_state "waiting-env-key" "GLASS_STREAM_KEY_FILE not found: ${GLASS_STREAM_KEY_FILE}"
    return 1
  fi

  if [[ -n "$stream_key_file" && -f "$stream_key_file" ]]; then
    return 0
  fi

  local -a key_files
  key_files=("$HOST_KEY_DIR"/*.key(N))
  if (( ${#key_files[@]} == 1 )); then
    stream_key_file="${key_files[1]}"
    log_sender_state "key-selected:$stream_key_file" "using existing stream key: $stream_key_file"
    return 0
  fi

  if (( ${#key_files[@]} > 1 )); then
    log_sender_state "waiting-key-select" "multiple stream keys found; reconnect adb or set GLASS_STREAM_KEY_FILE"
    return 1
  fi

  log_sender_state "waiting-key" "no stream key for BLE; connect over adb once"
  return 1
}

cleanup() {
  stop_sender "service exit"
}

trap cleanup EXIT
trap 'cleanup; exit 0' INT TERM
if adb_available; then
  "$ADB_BIN" start-server >/dev/null 2>&1 || true
fi
resolve_sender_command

while true; do
  desired_transport=""

  if [[ "$force_transport" == "ble" ]]; then
    if ! find_existing_stream_key; then
      stop_sender "stream key unavailable"
      sleep "$CHECK_INTERVAL"
      continue
    fi
    desired_transport="ble"
  elif [[ "$force_transport" == "tcp" ]]; then
    if resolve_serial_args && "$ADB_BIN" "${serial_args[@]}" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
      if [[ "$stream_key_device" != "$selected_device" || -z "$stream_key_file" || ! -f "$stream_key_file" ]]; then
        stop_sender "stream key refresh"
        if ! install_stream_key; then
          sleep "$CHECK_INTERVAL"
          continue
        fi
      fi
      desired_transport="tcp"
    else
      stream_key_device=""
      log_sender_state "waiting-tcp:$PORT" "tcp transport unavailable; waiting (forced --transport tcp)"
      stop_sender "tcp unavailable"
      sleep "$CHECK_INTERVAL"
      continue
    fi
  else
    if (( tcp_backoff_until > SECONDS )); then
      stream_key_device=""
      if ! find_existing_stream_key; then
        stop_sender "tcp backoff and no stream key"
        sleep "$CHECK_INTERVAL"
        continue
      fi
      desired_transport="ble"
    elif resolve_serial_args && "$ADB_BIN" "${serial_args[@]}" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
      if [[ "$stream_key_device" != "$selected_device" || -z "$stream_key_file" || ! -f "$stream_key_file" ]]; then
        stop_sender "stream key refresh"
        if ! install_stream_key; then
          sleep "$CHECK_INTERVAL"
          continue
        fi
      fi
      desired_transport="tcp"
    else
      stream_key_device=""
      if ! find_existing_stream_key; then
        stop_sender "adb unavailable and no stream key"
        sleep "$CHECK_INTERVAL"
        continue
      fi
      desired_transport="ble"
    fi
  fi

  if sender_running; then
    if [[ "$sender_transport" != "$desired_transport" || \
          "$sender_key_file" != "$stream_key_file" || \
          ( "$desired_transport" == "tcp" && "$sender_device" != "$selected_device" ) ]]; then
      stop_sender "transport switched to $desired_transport"
    fi
  elif [[ -n "$sender_pid" ]]; then
    if wait "$sender_pid"; then
      sender_status=0
    else
      sender_status=$?
    fi
    sender_pid=""
    if (( sender_started_at > 0 && (SECONDS - sender_started_at) < 5 && sender_status != 0 )); then
      if [[ "$sender_transport" == "tcp" && -z "$force_transport" ]]; then
        tcp_backoff_until=$((SECONDS + FAST_FAILURE_DELAY))
        log_sender_state "tcp-backoff:$sender_status" "tcp sender exited too quickly with status $sender_status; trying BLE for ${FAST_FAILURE_DELAY}s"
        continue
      fi
      log_sender_state "fast-fail:$sender_status" "sender exited too quickly with status $sender_status; retrying in ${FAST_FAILURE_DELAY}s"
      sleep "$FAST_FAILURE_DELAY"
      continue
    fi
  fi

  if ! sender_running; then
    start_sender "$desired_transport" || true
  fi

  sleep "$CHECK_INTERVAL"
done
