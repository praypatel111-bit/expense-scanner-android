#!/usr/bin/env bash
# Emulator smoke test. Opens the app, visits every tab, shares a receipt photo into it, picks a
# second one from the gallery, lets both be read (ML Kit + the rule-based reader; the 2.6 GB AI
# model isn't downloaded here), opens a bill, then saves the Excel report to Downloads and checks
# the file. Screenshots, the Excel file and the log land in shots/.
set -u
PKG=com.expensescanner.app
OUT=shots
mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
FAILED=""

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

dump() {
  for _ in 1 2 3 4 5 6; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

# Centre of the first element whose text or description matches $1 (exact match preferred).
coords() { dump && python3 ci/find.py "$OUT/ui.xml" "$1"; }

tap() {
  local c
  for _ in 1 2 3; do
    if c=$(coords "$1"); then
      echo "tap '$1' at $c"
      adb shell input tap $c
      return 0
    fi
    sleep 2
  done
  echo "NOT FOUND: $1"
  return 1
}

# Wait until no bill shows "Reading the bill…" (up to 2 minutes).
wait_read() {
  for i in $(seq 1 24); do
    sleep 5
    if dump && ! grep -q "Reading the bill" "$OUT/ui.xml"; then echo "read after ~$((i * 5)) s"; return 0; fi
  done
  echo "STILL READING after 2 minutes"
  return 1
}

adb logcat -c
adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity"
sleep 8
shot 01_bills_empty

tap "Report"; sleep 3; shot 02_report
tap "Settings"; sleep 3; shot 03_settings
tap "Bills"; sleep 3

# 1) Share a receipt photo into the app, the way Photos or Gmail would.
adb shell "run-as $PKG mkdir -p cache/captures"
adb shell "run-as $PKG sh -c 'cat > cache/captures/receipt.jpg'" < ci/receipt.jpg
adb shell "run-as $PKG ls -l cache/captures"
adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
  --eu android.intent.extra.STREAM "content://$PKG.files/captures/receipt.jpg"
sleep 5
shot 04_capture_sheet
tap "Meals" || FAILED="$FAILED share-sheet"
sleep 2
shot 05_saved
wait_read || FAILED="$FAILED read-shared"
shot 06_bills_one

tap "Tim Hortons" || tap "Unknown vendor" || FAILED="$FAILED open-bill"
sleep 4
shot 07_bill_detail
adb shell input swipe 540 1900 540 600 300; sleep 2; shot 08_bill_detail_more
adb shell input swipe 540 1900 540 600 300; sleep 2; shot 09_bill_detail_end
adb shell input keyevent 4; sleep 3
coords "Report" >/dev/null || { echo "Back from a bill didn't return to the list"; FAILED="$FAILED back"; }

# 2) Pick a second, long receipt from the gallery (system photo picker).
adb push ci/receipt_long.jpg /sdcard/Pictures/receipt_long.jpg
sleep 3
tap "Upload photos" || FAILED="$FAILED gallery-button"
sleep 5
shot 10_photo_picker
if tap "Photo taken"; then
  sleep 2
  shot 11_photo_picked
  tap "Add (" || tap "Add" || true
  sleep 5
  shot 12_capture_sheet_gallery
  tap "Office Supplies" || FAILED="$FAILED gallery-sheet"
  wait_read || FAILED="$FAILED read-gallery"
  shot 13_bills_two
else
  echo "Photo picker: no photo to tap (see 10_photo_picker.png)"
  FAILED="$FAILED photo-picker"
  adb shell input keyevent 4; sleep 2
fi

# 3) Report, then "Save Excel to my phone" through the system file picker.
tap "Report"; sleep 3; shot 14_report
adb shell input swipe 540 1900 540 700 300; sleep 2; shot 15_report_export
if tap "Save Excel to my phone"; then
  sleep 8
  shot 16_save_dialog
  tap "Save" || FAILED="$FAILED save-dialog"
  sleep 5
  shot 17_saved_excel
else
  FAILED="$FAILED save-excel-button"
fi
XLSX=$(adb shell "find /sdcard -name '*.xlsx' 2>/dev/null" | tr -d '\r' | head -1)
if [ -n "$XLSX" ]; then
  echo "Excel saved at $XLSX"
  adb pull "$XLSX" "$OUT/" >/dev/null
  python3 ci/check_xlsx.py "$OUT"/*.xlsx || FAILED="$FAILED excel-file"
else
  echo "No Excel file found on the emulator"
  FAILED="$FAILED excel-missing"
fi

adb logcat -d > "$OUT/logcat.txt"
echo "----- app log -----"
grep -E " ExpenseScanner|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" | tail -60
echo "-------------------"
grep -E "FATAL EXCEPTION" -A 40 "$OUT/logcat.txt" | grep -B 2 -A 38 "Process: $PKG" > "$OUT/crash.txt"
[ -s "$OUT/crash.txt" ] && { cat "$OUT/crash.txt"; FAILED="$FAILED crash"; }
adb shell pidof "$PKG" >/dev/null || FAILED="$FAILED not-running"

if [ -z "$FAILED" ]; then
  echo "SMOKE OK"
else
  echo "SMOKE FAILED:$FAILED"
  exit 1
fi
