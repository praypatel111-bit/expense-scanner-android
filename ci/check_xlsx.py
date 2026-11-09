"""Check an Excel report exported by the app: valid file, expected sheets, rows and photos."""
import sys
import zipfile

import openpyxl

ok = True
for path in sys.argv[1:]:
    z = zipfile.ZipFile(path)
    bad = z.testzip()
    media = [n for n in z.namelist() if n.startswith("xl/media/")]
    print(f"{path}: {'zip ok' if bad is None else 'BAD ENTRY ' + bad}, {len(media)} photos")
    wb = openpyxl.load_workbook(path)
    for ws in wb.worksheets:
        print(f"  sheet {ws.title!r}: {ws.max_row} rows x {ws.max_column} cols, {len(ws._images)} images")
    for name in ("Summary", "Expenses", "Line items"):
        if name not in wb.sheetnames:
            print(f"  MISSING sheet {name}")
            ok = False
    if "Expenses" in wb.sheetnames:
        for row in wb["Expenses"].iter_rows(min_row=1, max_row=4, values_only=True):
            print("   ", [c for c in row[:10]])
    ok = ok and bad is None
sys.exit(0 if ok else 1)
