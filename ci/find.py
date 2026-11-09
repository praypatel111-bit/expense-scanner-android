"""Print the centre of the first on-screen element whose text matches, from a uiautomator dump.

Usage: python3 find.py ui.xml "Text to find"
Exact (case-insensitive) matches win over partial ones. Exits 1 when nothing matches.
"""
import html
import re
import sys

xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
needle = sys.argv[2].strip().lower()
nodes = []
for m in re.finditer(r"<node\b[^>]*>", xml):
    node = m.group(0)
    text = re.search(r'\btext="([^"]*)"', node)
    desc = re.search(r'\bcontent-desc="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not bounds:
        continue
    x1, y1, x2, y2 = map(int, bounds.groups())
    if x2 <= x1 or y2 <= y1:
        continue
    for label in (text.group(1) if text else "", desc.group(1) if desc else ""):
        label = html.unescape(label).strip().lower()
        if label:
            nodes.append((label, (x1 + x2) // 2, (y1 + y2) // 2))

for exact in (True, False):
    for label, x, y in nodes:
        if (label == needle) if exact else (needle in label):
            print(x, y)
            sys.exit(0)
sys.exit(1)
