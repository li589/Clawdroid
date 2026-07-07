#!/system/bin/sh

ui_print() { echo "$1"; }

MODULE_ID="clawruntime"

# Let the manager extract files normally, then we fix explicit permissions below.

ui_print "*******************************"
ui_print "     ClawRuntime Installer     "
ui_print "*******************************"
ui_print "- Module: ClawRuntime"
ui_print "- Installing Magisk-side scaffold"
ui_print "- Canonical module ID: $MODULE_ID"
ui_print "- ClawRuntime binary must be packaged into /bin before release"

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/service.sh" 0 0 0755
  set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
  set_perm "$MODPATH/customize.sh" 0 0 0755
  set_perm "$MODPATH/verify.sh" 0 0 0755

  if [ -d "$MODPATH/bin" ]; then
    set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
    if [ -f "$MODPATH/bin/clawdroid-runtime" ]; then
      set_perm "$MODPATH/bin/clawdroid-runtime" 0 0 0755
    fi
  fi
}

set_permissions
