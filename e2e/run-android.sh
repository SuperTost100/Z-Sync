#!/usr/bin/env bash
# Runs the demo-mode Maestro flows against a booted emulator or device with
# the app installed. Fork-only test tooling; not part of upstream.
set -euo pipefail
cd "$(dirname "$0")/maestro"
adb shell settings put global hide_error_dialogs 1 >/dev/null
failed=0
for flow in 01_demo_spaces 02_address_rules 03_pin_then_navigate; do
  if maestro test "$flow.yaml" >/dev/null 2>&1; then echo "PASS $flow"; else echo "FAIL $flow"; failed=1; fi
done
# The browser must survive a configuration change (theme switch).
maestro test 04a_open_browser.yaml >/dev/null 2>&1
night=$(adb shell cmd uimode night | grep -o 'yes\|no')
adb shell cmd uimode night "$([ "$night" = yes ] && echo no || echo yes)" >/dev/null
sleep 6
if maestro test 04b_browser_still_open.yaml >/dev/null 2>&1; then echo "PASS 04_config_change"; else echo "FAIL 04_config_change"; failed=1; fi
adb shell cmd uimode night "$night" >/dev/null
exit $failed
