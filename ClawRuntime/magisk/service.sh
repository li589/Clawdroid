#!/system/bin/sh

MODDIR=${0%/*}
RUNTIME_BIN="$MODDIR/bin/clawdroid-runtime"
CONFIG_FILE="$MODDIR/config/runtime.yaml"
RUNTIME_DIR="/data/local/tmp/clawdroid"
LOG_DIR="$RUNTIME_DIR/logs"
LOG_FILE="$LOG_DIR/runtime-service.log"
AUDIT_DIR="$RUNTIME_DIR/audit"
AUDIT_FILE="$AUDIT_DIR/runtime-audit.log"
WEBROOT_DIR="$MODDIR/webroot"
STATUS_FILE="$WEBROOT_DIR/status.json"
VERIFY_FILE="$WEBROOT_DIR/verify.txt"
VERIFY_JSON_FILE="$WEBROOT_DIR/verify.json"
SERVICE_TAIL_FILE="$WEBROOT_DIR/runtime-service.tail.txt"
AUDIT_TAIL_FILE="$WEBROOT_DIR/runtime-audit.tail.txt"
STATE_LOOP_PID_FILE="$RUNTIME_DIR/webui-state.pid"
STATE_NOTE_FILE="$RUNTIME_DIR/webui-state.note"

mkdir -p "$LOG_DIR"
mkdir -p "$AUDIT_DIR"
mkdir -p "$WEBROOT_DIR"

BIN_FILE="$RUNTIME_BIN"
CONFIG_PATH="$CONFIG_FILE"

chmod 0755 "$BIN_FILE" 2>/dev/null

write_text_file() {
  target="$1"
  content="$2"
  printf '%s\n' "$content" > "$target"
  chmod 0644 "$target" 2>/dev/null
}

set_state_note() {
  write_text_file "$STATE_NOTE_FILE" "$1"
}

current_state_note() {
  if [ -f "$STATE_NOTE_FILE" ]; then
    cat "$STATE_NOTE_FILE"
  else
    echo "service initializing"
  fi
}

read_yaml_scalar() {
  key="$1"
  if [ ! -f "$CONFIG_PATH" ]; then
    return
  fi
  sed -n "s/^[[:space:]]*$key:[[:space:]]*\"\\{0,1\\}\\([^\"#]*\\)\"\\{0,1\\}.*/\\1/p" "$CONFIG_PATH" | head -n 1 | sed 's/[[:space:]]*$//'
}

json_escape() {
  printf '%s' "$1" | sed ':a;N;$!ba;s/\\/\\\\/g;s/"/\\"/g;s/\n/\\n/g'
}

find_runtime_pid() {
  pidof clawdroid-runtime 2>/dev/null | awk '{print $1}'
}

update_webui_snapshot() {
  state_note="$(current_state_note)"
  runtime_pid="$(find_runtime_pid)"
  runtime_running="false"
  runtime_state="stopped"
  if [ -n "$runtime_pid" ]; then
    runtime_running="true"
    runtime_state="running"
  fi

  socket_name="$(read_yaml_scalar socket_name)"
  screenshot_enabled="$(read_yaml_scalar screenshot_enabled)"
  file_bridge_enabled="$(read_yaml_scalar file_bridge_enabled)"
  [ -n "$socket_name" ] || socket_name="unknown"
  [ -n "$screenshot_enabled" ] || screenshot_enabled="unknown"
  [ -n "$file_bridge_enabled" ] || file_bridge_enabled="unknown"

  verify_exit_code=127
  if [ -x "$MODDIR/verify.sh" ]; then
    VERIFY_JSON_OUT="$VERIFY_JSON_FILE" "$MODDIR/verify.sh" > "$VERIFY_FILE" 2>&1
    verify_exit_code=$?
  else
    write_text_file "$VERIFY_FILE" "[WARN] verify.sh missing"
    {
      printf '%s\n' "{"
      printf '  "module_id": "%s",\n' "$(json_escape "clawruntime")"
      printf '  "status": "%s",\n' "$(json_escape "missing")"
      printf '  "summary": "%s",\n' "$(json_escape "verify.sh missing")"
      printf '  "pass_count": 0,\n'
      printf '  "warning_count": 1,\n'
      printf '  "failure_count": 0,\n'
      printf '  "checks": [\n'
      printf '    { "level": "warn", "message": "verify.sh missing" }\n'
      printf '  ]\n'
      printf '%s\n' "}"
    } > "$VERIFY_JSON_FILE"
  fi

  if [ -f "$LOG_FILE" ]; then
    tail -n 80 "$LOG_FILE" > "$SERVICE_TAIL_FILE"
  else
    write_text_file "$SERVICE_TAIL_FILE" "runtime-service.log not found"
  fi

  if [ -f "$AUDIT_FILE" ]; then
    tail -n 80 "$AUDIT_FILE" > "$AUDIT_TAIL_FILE"
  else
    write_text_file "$AUDIT_TAIL_FILE" "runtime-audit.log not found"
  fi

  service_log_size=0
  audit_log_size=0
  if [ -f "$LOG_FILE" ]; then
    service_log_size=$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ')
  fi
  if [ -f "$AUDIT_FILE" ]; then
    audit_log_size=$(wc -c < "$AUDIT_FILE" 2>/dev/null | tr -d ' ')
  fi

  updated_at="$(date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null)"
  updated_at_epoch="$(date '+%s' 2>/dev/null)"
  [ -n "$updated_at" ] || updated_at="unknown"
  [ -n "$updated_at_epoch" ] || updated_at_epoch="0"

  printf '%s\n' "{" > "$STATUS_FILE"
  printf '  "module_id": "%s",\n' "$(json_escape "clawruntime")" >> "$STATUS_FILE"
  printf '  "runtime_bin": "%s",\n' "$(json_escape "$BIN_FILE")" >> "$STATUS_FILE"
  printf '  "config_path": "%s",\n' "$(json_escape "$CONFIG_PATH")" >> "$STATUS_FILE"
  printf '  "log_file": "%s",\n' "$(json_escape "$LOG_FILE")" >> "$STATUS_FILE"
  printf '  "audit_file": "%s",\n' "$(json_escape "$AUDIT_FILE")" >> "$STATUS_FILE"
  printf '  "runtime_state": "%s",\n' "$(json_escape "$runtime_state")" >> "$STATUS_FILE"
  printf '  "runtime_running": %s,\n' "$runtime_running" >> "$STATUS_FILE"
  printf '  "runtime_pid": "%s",\n' "$(json_escape "${runtime_pid:-}")" >> "$STATUS_FILE"
  printf '  "socket_name": "%s",\n' "$(json_escape "$socket_name")" >> "$STATUS_FILE"
  printf '  "screenshot_enabled": "%s",\n' "$(json_escape "$screenshot_enabled")" >> "$STATUS_FILE"
  printf '  "file_bridge_enabled": "%s",\n' "$(json_escape "$file_bridge_enabled")" >> "$STATUS_FILE"
  printf '  "verify_exit_code": %s,\n' "$verify_exit_code" >> "$STATUS_FILE"
  printf '  "service_log_size": %s,\n' "${service_log_size:-0}" >> "$STATUS_FILE"
  printf '  "audit_log_size": %s,\n' "${audit_log_size:-0}" >> "$STATUS_FILE"
  printf '  "state_note": "%s",\n' "$(json_escape "$state_note")" >> "$STATUS_FILE"
  printf '  "updated_at": "%s",\n' "$(json_escape "$updated_at")" >> "$STATUS_FILE"
  printf '  "updated_at_epoch": %s\n' "${updated_at_epoch:-0}" >> "$STATUS_FILE"
  printf '%s\n' "}" >> "$STATUS_FILE"
  chmod 0644 "$STATUS_FILE" 2>/dev/null
  chmod 0644 "$VERIFY_FILE" "$VERIFY_JSON_FILE" "$SERVICE_TAIL_FILE" "$AUDIT_TAIL_FILE" 2>/dev/null
}

start_webui_snapshot_loop() {
  existing_pid=""
  if [ -f "$STATE_LOOP_PID_FILE" ]; then
    existing_pid="$(cat "$STATE_LOOP_PID_FILE" 2>/dev/null)"
  fi

  if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
    return
  fi

  (
    while true; do
      update_webui_snapshot
      sleep 15
    done
  ) &
  echo "$!" > "$STATE_LOOP_PID_FILE"
  chmod 0644 "$STATE_LOOP_PID_FILE" 2>/dev/null
}

set_state_note "service initializing"

if [ -f "$MODDIR/disable" ]; then
  set_state_note "module disabled by disable flag"
  update_webui_snapshot
  echo "[$(date)] clawruntime module disabled" >> "$LOG_FILE"
  exit 0
fi

if [ ! -f "$BIN_FILE" ]; then
  set_state_note "runtime binary missing"
  update_webui_snapshot
  echo "[$(date)] clawdroid runtime binary missing: $BIN_FILE" >> "$LOG_FILE"
  exit 0
fi

if [ ! -x "$BIN_FILE" ]; then
  set_state_note "runtime binary not executable"
  update_webui_snapshot
  echo "[$(date)] clawdroid runtime binary not executable after chmod: $BIN_FILE" >> "$LOG_FILE"
  exit 0
fi

BINARY_HASH_FILE="$MODDIR/config/.runtime_sha256"
if [ -f "$BINARY_HASH_FILE" ]; then
  EXPECTED_HASH="$(cat "$BINARY_HASH_FILE" 2>/dev/null | tr -d ' \n\r')"
  if [ -n "$EXPECTED_HASH" ]; then
    ACTUAL_HASH="$(sha256sum "$BIN_FILE" 2>/dev/null | awk '{print $1}')"
    if [ -z "$ACTUAL_HASH" ]; then
      set_state_note "runtime binary hash check failed"
      update_webui_snapshot
      echo "[$(date)] clawdroid runtime binary sha256sum failed: $BIN_FILE" >> "$LOG_FILE"
      exit 0
    fi
    if [ "$EXPECTED_HASH" != "$ACTUAL_HASH" ]; then
      set_state_note "runtime binary integrity check failed"
      update_webui_snapshot
      echo "[$(date)] clawdroid runtime binary INTEGRITY CHECK FAILED: $BIN_FILE" >> "$LOG_FILE"
      echo "[$(date)]   expected: $EXPECTED_HASH" >> "$LOG_FILE"
      echo "[$(date)]   actual:   $ACTUAL_HASH" >> "$LOG_FILE"
      exit 0
    fi
  fi
fi

set_state_note "launching runtime service"
update_webui_snapshot
start_webui_snapshot_loop

echo "[$(date)] starting clawdroid runtime with config $CONFIG_PATH" >> "$LOG_FILE"
"$BIN_FILE" --config "$CONFIG_PATH" >> "$LOG_FILE" 2>&1 &
set_state_note "runtime launch command submitted"
update_webui_snapshot
