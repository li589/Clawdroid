#!/system/bin/sh

MODDIR=${0%/*}
MODULE_PROP="$MODDIR/module.prop"
EXPECTED_ID="clawruntime"
PASS_COUNT=0
FAILURES=0
WARNINGS=0
CHECKS_JSON=""
FINAL_STATUS="pending"
FINAL_SUMMARY="verify has not finished"

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  append_check "pass" "$1"
  echo "[PASS] $1"
}

warn() {
  echo "[WARN] $1"
  WARNINGS=$((WARNINGS + 1))
  append_check "warn" "$1"
}

fail() {
  echo "[FAIL] $1"
  FAILURES=$((FAILURES + 1))
  append_check "fail" "$1"
}

read_prop_value() {
  sed -n "s/^$2=//p" "$1" | head -n 1
}

read_yaml_scalar() {
  if [ ! -f "$1" ]; then
    return
  fi
  sed -n "s/^[[:space:]]*$2:[[:space:]]*\"\\{0,1\\}\\([^\"#]*\\)\"\\{0,1\\}.*/\\1/p" "$1" | head -n 1 | sed 's/[[:space:]]*$//'
}

json_escape() {
  printf '%s' "$1" | tr '\r\n' '  ' | sed 's/\\/\\\\/g;s/"/\\"/g'
}

append_check() {
  level="$1"
  message="$2"
  entry=$(printf '{ "level": "%s", "message": "%s" }' \
    "$(json_escape "$level")" \
    "$(json_escape "$message")")
  if [ -n "$CHECKS_JSON" ]; then
    CHECKS_JSON="$CHECKS_JSON,
$entry"
  else
    CHECKS_JSON="$entry"
  fi
}

write_verify_json() {
  if [ -z "$VERIFY_JSON_OUT" ]; then
    return
  fi

  {
    printf '%s\n' "{"
    printf '  "module_id": "%s",\n' "$(json_escape "$EXPECTED_ID")"
    printf '  "status": "%s",\n' "$(json_escape "$FINAL_STATUS")"
    printf '  "summary": "%s",\n' "$(json_escape "$FINAL_SUMMARY")"
    printf '  "pass_count": %s,\n' "$PASS_COUNT"
    printf '  "warning_count": %s,\n' "$WARNINGS"
    printf '  "failure_count": %s,\n' "$FAILURES"
    printf '  "checks": [\n'
    if [ -n "$CHECKS_JSON" ]; then
      printf '%s\n' "$CHECKS_JSON"
    fi
    printf '  ]\n'
    printf '%s\n' "}"
  } > "$VERIFY_JSON_OUT"
}

check_file_present() {
  if [ -f "$1" ]; then
    pass "$2"
  else
    fail "$3"
  fi
}

check_dir_present() {
  if [ -d "$1" ]; then
    pass "$2"
  else
    fail "$3"
  fi
}

check_executable_file() {
  if [ ! -f "$1" ]; then
    fail "$2"
    return
  fi
  if [ -x "$1" ]; then
    pass "$3"
  else
    fail "$4"
  fi
}

if [ ! -f "$MODULE_PROP" ]; then
  fail "module.prop missing"
fi

check_executable_file "$MODDIR/service.sh" "service.sh missing" "service.sh present and executable" "service.sh present but not executable"
check_executable_file "$MODDIR/post-fs-data.sh" "post-fs-data.sh missing" "post-fs-data.sh present and executable" "post-fs-data.sh present but not executable"
check_executable_file "$MODDIR/verify.sh" "verify.sh missing" "verify.sh present and executable" "verify.sh present but not executable"
check_file_present "$MODDIR/sepolicy.rule" "sepolicy.rule present" "sepolicy.rule missing"
check_file_present "$MODDIR/config/runtime.yaml" "config/runtime.yaml present" "config/runtime.yaml missing"
check_file_present "$MODDIR/webroot/index.html" "webroot/index.html present" "webroot/index.html missing"
check_file_present "$MODDIR/webroot/status.json" "webroot/status.json present" "webroot/status.json missing"
check_file_present "$MODDIR/webroot/verify.json" "webroot/verify.json present" "webroot/verify.json missing"
check_file_present "$MODDIR/webroot/verify.txt" "webroot/verify.txt present" "webroot/verify.txt missing"

if [ -f "$MODDIR/customize.sh" ]; then
  if [ -x "$MODDIR/customize.sh" ]; then
    pass "customize.sh retained in module directory and executable"
  else
    warn "customize.sh retained in module directory but not executable"
  fi
else
  pass "customize.sh is installer-only and not required after installation"
fi

if [ -f "$MODULE_PROP" ]; then
  current_id=$(read_prop_value "$MODULE_PROP" "id")
  current_name=$(read_prop_value "$MODULE_PROP" "name")

  if [ "$current_id" = "$EXPECTED_ID" ]; then
    pass "module.prop uses canonical module ID: $EXPECTED_ID"
  else
    fail "module.prop id is '$current_id', expected '$EXPECTED_ID'"
  fi

  if [ "$current_name" = "ClawRuntime" ]; then
    pass "module.prop name is ClawRuntime"
  else
    warn "module.prop name is '$current_name'"
  fi
fi

check_dir_present "$MODDIR/bin" "bin directory present" "bin directory missing"
check_executable_file "$MODDIR/bin/clawdroid-runtime" "clawdroid-runtime binary missing" "clawdroid-runtime binary present and executable" "clawdroid-runtime binary present but not executable"

if [ -f "$MODDIR/config/runtime.yaml" ]; then
  socket_name=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "socket_name")
  shared_secret=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "shared_secret")
  allowed_signatures=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "allowed_signatures")
  screenshot_enabled=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "screenshot_enabled")
  file_bridge_enabled=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "file_bridge_enabled")
  shell_enabled=$(read_yaml_scalar "$MODDIR/config/runtime.yaml" "shell_enabled")

  if [ -n "$socket_name" ]; then
    pass "runtime.socket_name configured: $socket_name"
  else
    fail "runtime.socket_name missing in config/runtime.yaml"
  fi

  if [ -z "$shared_secret" ]; then
    fail "auth.shared_secret missing in config/runtime.yaml"
  elif [ "$shared_secret" = "clawdroid-dev-secret" ]; then
    warn "auth.shared_secret still uses legacy development secret"
  else
    pass "auth.shared_secret configured"
  fi

  if [ -n "$allowed_signatures" ]; then
    pass "auth.allowed_signatures configured"
  else
    warn "auth.allowed_signatures is empty; acceptable for local debug, not recommended for release packages"
  fi

  if [ "$screenshot_enabled" = "true" ]; then
    pass "capability.screenshot_enabled=true"
  else
    warn "capability.screenshot_enabled is '$screenshot_enabled'"
  fi

  if [ "$file_bridge_enabled" = "true" ]; then
    pass "capability.file_bridge_enabled=true"
  else
    warn "capability.file_bridge_enabled is '$file_bridge_enabled'"
  fi

  if [ "$shell_enabled" = "true" ]; then
    pass "capability.shell_enabled=true"
  else
    warn "capability.shell_enabled is '$shell_enabled'"
  fi
fi

if [ -d "/data/local/tmp/clawdroid" ]; then
  pass "runtime working directory present: /data/local/tmp/clawdroid"
else
  warn "runtime working directory not created yet: /data/local/tmp/clawdroid"
fi

if [ -d "/data/local/tmp/clawdroid/logs" ]; then
  pass "runtime log directory present: /data/local/tmp/clawdroid/logs"
else
  warn "runtime log directory not created yet: /data/local/tmp/clawdroid/logs"
fi

if [ -d "/data/local/tmp/clawdroid/audit" ]; then
  pass "runtime audit directory present: /data/local/tmp/clawdroid/audit"
else
  warn "runtime audit directory not created yet: /data/local/tmp/clawdroid/audit"
fi

if [ -d "$MODDIR/migration-state" ]; then
  warn "migration-state directory is still present"
fi

if [ "$FAILURES" -gt 0 ]; then
  FINAL_STATUS="failed"
  FINAL_SUMMARY="verify failed: $FAILURES failure(s), $WARNINGS warning(s)"
  write_verify_json
  echo "$FINAL_SUMMARY"
  exit 1
fi

FINAL_STATUS="passed"
FINAL_SUMMARY="verify passed: 0 failure(s), $WARNINGS warning(s)"
write_verify_json
echo "$FINAL_SUMMARY"
exit 0
