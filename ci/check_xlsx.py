"""Check what the app exported: an Excel report, or a .zip with the report plus a Receipts folder.

Excel: valid file, sheets Bills/Summary/Line items with Bills first, every "Open receipt" link
points at a photo caption (or a photo file in the .zip). Exits 1 on any problem.
"""
import io
import re
import sys
import zipfile

import openpyxl

ok = True


def problem(msg):
    global ok
    ok = False
    print("  PROBLEM:", msg)


def check_book(data, label, files=None):
    wb = openpyxl.load_workbook(io.BytesIO(data))
    print(f"{label}: sheets {wb.sheetnames}")
    if wb.sheetnames[:3] != ["Bills", "Summary", "Line items"]:
        problem("expected Bills, Summary, Line items (Bills first)")
        return
    ws = wb["Bills"]
    head = [c.value for c in ws[1]]
    col = head.index("Receipt photo")
    print(f"  Bills: {ws.max_row} rows, {len(ws._images)} images")
    for row in ws.iter_rows(min_row=2):
        if not row[0].value or not re.match(r"R-\d+", str(row[0].value)):
            break
        cell = row[col]
        hl = cell.hyperlink
        target = (hl.location or hl.target) if hl else None
        print(f"   {row[0].value} {row[2].value!r} total={row[4].value} photo={cell.value!r} -> {target}")
        if cell.value == "Open receipt":
            if not hl:
                problem(f"{row[0].value}: 'Open receipt' without a link")
            elif hl.location:
                m = re.match(r"'Bills'!A(\d+)$", hl.location)
                caption = ws.cell(int(m.group(1)), 1).value if m else None
                if not caption or not str(caption).startswith(str(row[0].value)):
                    problem(f"{row[0].value}: link lands on {caption!r}")
            elif files is not None and hl.target not in files:
                problem(f"{row[0].value}: {hl.target} is not in the .zip")


for path in sys.argv[1:]:
    raw = open(path, "rb").read()
    z = zipfile.ZipFile(io.BytesIO(raw))
    if z.testzip() is not None:
        problem(f"{path}: damaged file")
        continue
    names = z.namelist()
    if path.endswith(".zip"):
        books = [n for n in names if n.endswith(".xlsx")]
        photos = [n for n in names if n.startswith("Receipts/") and n.endswith(".jpg")]
        print(f"{path}: {len(books)} Excel file(s), {len(photos)} photos in Receipts/")
        if len(books) != 1:
            problem("expected exactly one Excel file in the .zip")
            continue
        check_book(z.read(books[0]), "  " + books[0], set(photos))
    else:
        check_book(raw, path)

sys.exit(0 if ok else 1)
