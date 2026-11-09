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
: > "$OUT/failures.txt"
# Failures go to a file so steps that run in a subshell can record them too.
fail() { echo "$1" >> "$OUT/failures.txt"; }
# Things worth a look that don't fail the run (emulator quirks, optional steps).
warn() { echo "WARNING: $1"; echo "$1" >> "$OUT/warnings.txt"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

# Screen contents into $OUT/ui.xml. A system "… isn't responding" dialog blocks every tap, so it
# is closed here (and recorded if it's about this app).
dump() {
  local who btn c
  for _ in 1 2 3 4 5 6 7 8; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      who=$(grep -oE 'text="[^"]*responding[^"]*"' "$OUT/ui.xml" | head -1)
      if [ -z "$who" ]; then return 0; fi
      echo "System dialog: $who" >&2
      case "$who" in
        *"Expense Scanner"*) fail app-not-responding; btn="Wait" ;;
        *) btn="Close app" ;;
      esac
      c=$(python3 ci/find.py "$OUT/ui.xml" "$btn") && adb shell input tap $c
      sleep 3
    else
      sleep 2
    fi
  done
  return 1
}

# Centre of the first element whose text or description matches $1 (exact match preferred).
coords() { dump && python3 ci/find.py "$OUT/ui.xml" "$1"; }

tap() {
  local c
  for _ in 1 2 3; do
    if c=$(coords "$1"); then
      echo "tap '$1' at $c" >&2
      adb shell input tap $c
      return 0
    fi
    sleep 2
  done
  echo "NOT FOUND: $1" >&2
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
# Let the freshly booted system settle, and clear any dialog it left behind.
adb shell input keyevent 82 >/dev/null 2>&1
sleep 25
dump >/dev/null || true
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
tap "Meals" || fail share-sheet
sleep 2
shot 05_saved
wait_read || fail read-shared
shot 06_bills_one

if tap "Tim Hortons" || tap "Unknown vendor"; then
  sleep 4
  shot 07_bill_detail
  adb shell input swipe 540 1900 540 600 300; sleep 2; shot 08_bill_detail_more
  adb shell input swipe 540 1900 540 600 300; sleep 2; shot 09_bill_detail_end
  adb shell input keyevent 4; sleep 3
  coords "Report" >/dev/null || { echo "Back from a bill didn't return to the list"; fail back; }
else
  fail open-bill
fi
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2

# 2) Pick a second, long receipt from the gallery (system photo picker).
PIC=/storage/emulated/0/Pictures/receipt_long.jpg
adb push ci/receipt_long.jpg "$PIC"
# Make sure the photo library knows about the new file.
adb shell content call --uri content://media --method scan_file --arg "$PIC" >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$PIC" >/dev/null 2>&1 || true
sleep 5
tap "Upload photos" || fail gallery-button
sleep 5
shot 10_photo_picker
picked=1
if ! tap "Photo taken"; then
  if dump && ! grep -q "No photos" "$OUT/ui.xml"; then adb shell input tap 180 650; else picked=0; fi
fi
if [ "$picked" = 1 ]; then
  sleep 2
  shot 11_photo_picked
  tap "Add (" || tap "Add" || true
  sleep 5
  shot 12_capture_sheet_gallery
  tap "Office Supplies" || fail gallery-sheet
  wait_read || fail read-gallery
  shot 13_bills_two
else
  warn "photo picker showed no photos (emulator didn't index the pushed file)"
fi
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2

# 3) Report, then "Save Excel to my phone" through the system file picker.
tap "Report"; sleep 3; shot 14_report
adb shell input swipe 540 1900 540 700 300; sleep 2; shot 15_report_export
if tap "Save Excel to my phone"; then
  sleep 8
  shot 16_save_dialog
  tap "Save" || fail save-dialog
  sleep 5
  shot 17_saved_excel
else
  fail save-excel-button
fi
XLSX=$(adb shell "ls /storage/emulated/0/Download/*.xlsx 2>/dev/null" | tr -d '\r' | head -1)
if [ -n "$XLSX" ]; then
  echo "Excel saved at $XLSX"
  adb pull "$XLSX" "$OUT/" >/dev/null
  python3 ci/check_xlsx.py "$OUT"/*.xlsx || fail excel-file
else
  echo "No Excel file found on the emulator"
  fail excel-missing
fi

# 4) Optional: download the AI model in the app and let it read the long receipt.
if [ "${SMOKE_AI:-0}" = "1" ]; then
  adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
  tap "Settings"; sleep 3
  if tap "Download on Wi-Fi"; then
    for i in $(seq 1 90); do
      sleep 10
      if dump && grep -q "Ready. New bills are read by AI" "$OUT/ui.xml"; then echo "model ready after ~$((i * 10)) s"; break; fi
      [ $((i % 12)) -eq 1 ] && shot "20_ai_download_$i"
    done
  else
    fail ai-download-button
  fi
  shot 21_ai_ready
  tap "Bills"; sleep 2
  adb shell "run-as $PKG sh -c 'cat > cache/captures/receipt_long.jpg'" < ci/receipt_long.jpg
  adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
    --eu android.intent.extra.STREAM "content://$PKG.files/captures/receipt_long.jpg"
  sleep 5
  tap "Let AI pick" || fail ai-sheet
  for i in $(seq 1 72); do
    sleep 5
    if dump && ! grep -q "Reading the bill" "$OUT/ui.xml"; then echo "AI read after ~$((i * 5)) s"; break; fi
  done
  shot 22_ai_read
  adb logcat -d | grep -E " ExpenseScanner" | grep -E "AI (found|not used)|AI reader failed" | tail -3
  adb logcat -d | grep -qE "ExpenseScanner: .*AI found" || fail ai-not-used
  if tap "Staples"; then
    sleep 4; shot 23_ai_bill
    adb shell input swipe 540 1900 540 600 300; sleep 2; shot 24_ai_bill_more
    adb shell input swipe 540 1900 540 600 300; sleep 2; shot 25_ai_bill_end
    adb shell input keyevent 4; sleep 2
  fi
fi

adb logcat -d > "$OUT/logcat.txt"
echo "----- app log -----"
grep -E " ExpenseScanner|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" | tail -60
echo "-------------------"
grep -E "FATAL EXCEPTION" -A 40 "$OUT/logcat.txt" | grep -B 2 -A 38 "Process: $PKG" > "$OUT/crash.txt"
[ -s "$OUT/crash.txt" ] && { cat "$OUT/crash.txt"; fail crash; }
adb shell pidof "$PKG" >/dev/null || fail not-running

[ -s "$OUT/warnings.txt" ] && echo "Warnings: $(sort -u "$OUT/warnings.txt" | tr '\n' ';')"
if [ ! -s "$OUT/failures.txt" ]; then
  echo "SMOKE OK"
else
  echo "SMOKE FAILED: $(sort -u "$OUT/failures.txt" | tr '\n' ' ')"
  exit 1
fi
