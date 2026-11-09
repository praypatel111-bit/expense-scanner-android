#!/usr/bin/env bash
# Emulator smoke test: open the app, visit every tab, share a receipt photo into it, let it be
# read (ML Kit + the rule-based reader; the 2.6 GB AI model isn't downloaded here), open the bill.
# Screenshots and the log land in shots/. Fails if the app crashes or stops running.
set -u
PKG=com.expensescanner.app
OUT=shots
mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

# Centre of the first element whose text or description matches $1 (exact match preferred).
coords() {
  for _ in 1 2 3 4 5 6; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      python3 ci/find.py "$OUT/ui.xml" "$1" && return 0
    fi
    sleep 2
  done
  return 1
}

tap() {
  local c
  c=$(coords "$1") || { echo "NOT FOUND: $1"; return 1; }
  echo "tap '$1' at $c"
  adb shell input tap $c
}

adb logcat -c
adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity"
sleep 8
shot 01_bills_empty

tap "Report"; sleep 3; shot 02_report
tap "Settings"; sleep 3; shot 03_settings
adb shell input swipe 540 1900 540 700 300; sleep 2; shot 03b_settings_scrolled
tap "Bills"; sleep 3

# Share a receipt photo into the app, the way Photos or Gmail would.
adb push ci/receipt.jpg "/sdcard/Android/data/$PKG/files/receipt.jpg"
adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
  --eu android.intent.extra.STREAM "file:///sdcard/Android/data/$PKG/files/receipt.jpg"
sleep 5
shot 04_capture_sheet

tap "Meals"
sleep 25
shot 05_bills_after_read

tap "Tim Hortons" || tap "Unknown vendor" || tap "Meals"
sleep 4
shot 06_bill_detail
adb shell input swipe 540 1900 540 600 300; sleep 2; shot 07_bill_detail_scrolled
adb shell input swipe 540 1900 540 600 300; sleep 2; shot 08_bill_detail_bottom
adb shell input keyevent 4; sleep 3

tap "Report"; sleep 3; shot 09_report_with_bill
adb shell input swipe 540 1900 540 700 300; sleep 2; shot 10_report_export

adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime" -A 40 "$OUT/logcat.txt" > "$OUT/crash.txt"
if adb shell pidof "$PKG" >/dev/null && [ ! -s "$OUT/crash.txt" ]; then
  echo "SMOKE OK"
else
  echo "SMOKE FAILED"
  cat "$OUT/crash.txt"
  exit 1
fi
