#!/usr/bin/env python3
"""Turn the standard Site Visit Sheet .docx into a docxtemplater template.

Input : DSO Tower 6 (1).docx  (the real standard, any filled sample)
Output: apk-build/www/template.docx  with {placeholders} + row loops.
"""
import sys, copy
from docx import Document
from docx.oxml.ns import qn

SRC = sys.argv[1] if len(sys.argv) > 1 else "/Users/alihashi/Downloads/DSO Tower 6 (1).docx"
OUT = sys.argv[2] if len(sys.argv) > 2 else "apk-build/www/template.docx"

doc = Document(SRC)
T = doc.tables


def set_cell(cell, text):
    """Replace all text in a cell with one templated string, keep first run's font."""
    p = cell.paragraphs[0]
    runs = p.runs
    if runs:
        runs[0].text = text
        for r in runs[1:]:
            r.text = ""
    else:
        p.add_run(text)
    for extra in cell.paragraphs[1:]:
        extra._element.getparent().remove(extra._element)


def set_para(p, text):
    runs = p.runs
    if runs:
        runs[0].text = text
        for r in runs[1:]:
            r.text = ""
    else:
        p.add_run(text)


def del_rows_from(tbl, start):
    for row in list(tbl.rows[start:]):
        tbl._tbl.remove(row._tr)


# ---- 0: header (Document Ref / Prepared By / Date / Version) ----
h = T[0]
for c, ph in zip(h.rows[1].cells, ["{doc_ref}", "{prepared_by}", "{date}", "{version}"]):
    set_cell(c, ph)

# ---- 1: project block ----
p1 = T[1]
for r, ph in zip(p1.rows, ["{project}", "{site_name}", "{client}", "{address}", "{maps_link}"]):
    set_cell(r.cells[1], ph)

# ---- 2 + 3: 1.1 checklist (Yes/No) ----
n = 0
for tbl in (T[2], T[3]):
    for row in tbl.rows:
        set_cell(row.cells[1], "{chk_%d}" % n)
        n += 1
assert n == 13, n

# ---- note paragraph: "The Backside wall ..." ----
for para in doc.paragraphs:
    if "Backside wall" in para.text:
        set_para(para, "{checklist_note}")
        break

# ---- 4: Technical Findings - Elevator ----
te = T[4]
for r, ph in zip(te.rows, ["{elev_oem}", "{elev_model}", "{elev_id}", "{elev_maint}", "{plug_points}"]):
    set_cell(r.cells[1], ph)

# ---- railing measurements ----
# Top + Rear live in body paragraphs before table #5; Left + Right inside table #5.
def fill_measure(paras, letters, prefix):
    """paras: list of paragraphs whose text should become the templated Measurements line(s)."""
    joined = "Measurements:\t" + "\t".join("%s: {%s_%s}" % (L, prefix, L) for L in letters)
    set_para(paras[0], joined)
    for p in paras[1:]:
        set_para(p, "")

body_paras = doc.paragraphs
meas_idx = [i for i, p in enumerate(body_paras) if p.text.strip().startswith("Measurements:")]
# first two are Top, Rear (each may have a continuation line right after)
top_p = [body_paras[meas_idx[0]], body_paras[meas_idx[0] + 1]]
rear_p = [body_paras[meas_idx[1]], body_paras[meas_idx[1] + 1]]
fill_measure(top_p, list("ABCDEFG"), "r_top")
fill_measure(rear_p, list("ABCDE"), "r_rear")

lr = T[5]
for cell, letters, prefix in ((lr.rows[0].cells[1], list("ABCDE"), "r_left"),
                              (lr.rows[1].cells[1], list("ABCDE"), "r_right")):
    allp = list(cell.paragraphs)
    for i, p in enumerate(allp):
        if p.text.strip().startswith("Measurements:"):
            fill_measure(allp[i:i + 2], letters, prefix)
            break
set_cell(lr.rows[2].cells[1], "{rail_note}")

# ---- 6: Network / App / Feasibility ----
nw = T[6]
set_cell(nw.rows[0].cells[1], "{networks}")
set_cell(nw.rows[1].cells[1], "{survey_point}")
set_cell(nw.rows[2].cells[1], "{feasibility}")

# ---- 7/8/9: RF tables -> single looping data row ----
RF_COLS = ["du_rsrp", "du_rsrq", "du_sinr", "du_band", "du_dl", "du_ul",
           "et_rsrp", "et_rsrq", "et_sinr", "et_band", "et_dl", "et_ul"]
for tbl, tag in ((T[7], "rf1"), (T[8], "rf2"), (T[9], "rf3")):
    row = tbl.rows[2]                       # first data row = template
    set_cell(row.cells[0], "{#%s}{loc}" % tag)
    for ci, key in enumerate(RF_COLS, start=1):
        set_cell(row.cells[ci], "{%s}" % key)
    set_cell(row.cells[12], "{et_ul}{/%s}" % tag)
    del_rows_from(tbl, 3)

# ---- 10: Integration checks + pinout ----
ic = T[10]
set_cell(ic.rows[0].cells[1], "{btn_sku}")
set_cell(ic.rows[1].cells[1], "{btn_connectors}")
for i, row in enumerate(ic.rows[3:7], start=1):
    set_cell(row.cells[2], "{pin%d_use}" % i)
    set_cell(row.cells[3], "{pin%d_color}" % i)

# ---- 11..15: photo pages -> one looping block ----
photo_tbls = T[11:16]
first = photo_tbls[0]
set_cell(first.rows[1].cells[0], "{p_desc}")
set_cell(first.rows[1].cells[1], "{p_file}")
del_rows_from(first, 2)                      # drop the blank + DUPLICATE rows

def text_para(model_p, text, page_break=False):
    p = copy.deepcopy(model_p)
    for r in list(p.findall(qn("w:r"))):
        p.remove(r)
    if page_break:
        br_r = p.makeelement(qn("w:r"), {})
        br = p.makeelement(qn("w:br"), {})
        br.set(qn("w:type"), "page")
        br_r.append(br)
        p.append(br_r)
    run = p.makeelement(qn("w:r"), {})
    tn = p.makeelement(qn("w:t"), {})
    tn.set(qn("xml:space"), "preserve")
    tn.text = text
    run.append(tn)
    p.append(run)
    return p

model = first._tbl.getprevious()
# loop opens with a page break so every photo starts a fresh page (like the original)
open_p = text_para(model, "{#photos}", page_break=True)
close_p = text_para(model, "{/photos}")
first._tbl.addprevious(open_p)
first._tbl.addnext(close_p)

# remove the other sample photo tables + their trailing paragraphs / DUPLICATE lines
for tbl in photo_tbls[1:]:
    nxt = tbl._tbl.getnext()
    tbl._tbl.getparent().remove(tbl._tbl)
    while nxt is not None and nxt.tag == qn("w:p") and (nxt.text or "").strip() in ("", "DUPLICATE THIS PAGE AS MANY TIMES AS NEEDED"):
        rm = nxt
        nxt = nxt.getnext()
        rm.getparent().remove(rm)

doc.save(OUT)
print("wrote", OUT)
print("placeholders: doc_ref prepared_by date version project site_name client address maps_link")
print("  chk_0..chk_12  checklist_note")
print("  elev_oem elev_model elev_id elev_maint plug_points")
print("  r_top_A..G  r_rear_A..E  r_left_A..E  r_right_A..E  rail_note")
print("  networks survey_point feasibility")
print("  rf1/rf2/rf3 = [{loc, du_rsrp..du_ul, et_rsrp..et_ul}]")
print("  btn_sku btn_connectors  pin1_use..pin4_color")
print("  photos = [{p_desc, p_file}]")
