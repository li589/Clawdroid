#!/system/bin/sh

MODDIR=${0%/*}
RUNTIME_DIR="/data/local/tmp/clawdroid"

mkdir -p "$RUNTIME_DIR"
mkdir -p "$RUNTIME_DIR/logs"
mkdir -p "$RUNTIME_DIR/audit"

chmod 0700 "$RUNTIME_DIR"
chmod 0755 "$MODDIR/service.sh" 2>/dev/null
chmod 0755 "$MODDIR/post-fs-data.sh" 2>/dev/null
chmod 0755 "$MODDIR/customize.sh" 2>/dev/null
chmod 0755 "$MODDIR/verify.sh" 2>/dev/null
if [ -f "$MODDIR/bin/clawdroid-runtime" ]; then
  chmod 0755 "$MODDIR/bin/clawdroid-runtime" 2>/dev/null
fi
