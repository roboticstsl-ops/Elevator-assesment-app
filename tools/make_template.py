#!/usr/bin/env python3
"""Turn the standard Site Visit Sheet .docx into a docxtemplater template.

Input : DSO Tower 6_Templet.docx  (the standard, with the client's <<tokens>>)
Output: apk-build/www/template.docx  with {placeholders} + row loops.

The client's <<tokens>> mark editable spots; we replace them (and the fixed
sample data) positionally with docxtemplater {tags}.
"""
import sys, re, copy
from docx import Document
from docx.oxml.ns import qn

SRC = sys.argv[1] if len(sys.argv) > 1 else "/Users/alihashi/Downloads/DSO Tower 6_Templet.docx"
OUT = sys.argv[2] if len(sys.argv) > 2 else "apk-build/www/template.docx"

doc = Document(SRC)
T = doc.tables


def set_cell(cell, text):
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


# ---- 0: header ----
for c, ph in zip(T[0].rows[1].cells, ["{doc_ref}", "{prepared_by}", "{date}", "{version}"]):
    set_cell(c, ph)

# ---- 1: project block ----
for r, ph in zip(T[1].rows, ["{project}", "{site_name}", "{client}", "{address}", "{maps_link}"]):
    set_cell(r.cells[1], ph)

# ---- 2 + 3: 1.1 checklist ----
n = 0
for tbl in (T[2], T[3]):
    for row in tbl.rows:
        set_cell(row.cells[1], "{chk_%d}" % n)
        n += 1
assert n == 13, n

# checklist free-text note: add a paragraph right after checklist table #3
note_p = copy.deepcopy(T[3]._tbl.getnext())
if note_p is not None and note_p.tag == qn("w:p"):
    for rr in list(note_p.findall(qn("w:r"))):
        note_p.remove(rr)
    run = note_p.makeelement(qn("w:r"), {})
    tn = note_p.makeelement(qn("w:t"), {})
    tn.set(qn("xml:space"), "preserve")
    tn.text = "{checklist_note}"
    run.append(tn)
    note_p.append(run)
    T[3]._tbl.addnext(note_p)

# ---- 4: Technical Findings - Elevator ----
for r, ph in zip(T[4].rows, ["{elev_oem}", "{elev_model}", "{elev_id}", "{elev_maint}", "{plug_points}"]):
    set_cell(r.cells[1], ph)

# ---- railing measurement lines (numbers only; diagram untouched) ----
def fill_measure(paras, letters, prefix):
    tag = lambda L: "%s: {%s_%s}mm" % (L, prefix, L)
    if len(letters) > 4 and len(paras) > 1:
        set_para(paras[0], "Measurements:\t" + "\t".join(tag(L) for L in letters[:4]))
        set_para(paras[1], "\t".join(tag(L) for L in letters[4:]))
    else:
        set_para(paras[0], "Measurements:\t" + "\t".join(tag(L) for L in letters))
        for p in paras[1:]:
            if re.match(r"^[A-G]:", p.text.strip()):
                set_para(p, "")

bp = doc.paragraphs
midx = [i for i, p in enumerate(bp) if p.text.strip().startswith("Measurements:")]
fill_measure([bp[midx[0]], bp[midx[0] + 1]], list("ABCDEFG"), "r_top")
fill_measure([bp[midx[1]], bp[midx[1] + 1]], list("ABCDE"), "r_rear")

for cell, letters, prefix in ((T[5].rows[0].cells[1], list("ABCDE"), "r_left"),
                              (T[5].rows[1].cells[1], list("ABCDE"), "r_right")):
    ps = list(cell.paragraphs)
    for i, p in enumerate(ps):
        if p.text.strip().startswith("Measurements:"):
            fill_measure(ps[i:i + 2], letters, prefix)
            break
set_cell(T[5].rows[2].cells[1], "{rail_note}")

# ---- 6: Network / App / Feasibility ----
set_cell(T[6].rows[0].cells[1], "{networks}")
set_cell(T[6].rows[1].cells[1], "{survey_point}")
set_cell(T[6].rows[2].cells[1], "{feasibility}")

# ---- 7/8/9: RF tables -> single looping data row ----
RF_COLS = ["du_rsrp", "du_rsrq", "du_sinr", "du_band", "du_dl", "du_ul",
           "et_rsrp", "et_rsrq", "et_sinr", "et_band", "et_dl", "et_ul"]
for tbl, tag in ((T[7], "rf1"), (T[8], "rf2"), (T[9], "rf3")):
    row = tbl.rows[2]
    set_cell(row.cells[0], "{#%s}{loc}" % tag)
    for ci, key in enumerate(RF_COLS, start=1):
        set_cell(row.cells[ci], "{%s}" % key)
    set_cell(row.cells[12], "{et_ul}{/%s}" % tag)
    del_rows_from(tbl, 3)

# ---- 10: Integration + pinout ----
set_cell(T[10].rows[0].cells[1], "{btn_sku}")
set_cell(T[10].rows[1].cells[1], "{btn_connectors}")
for i, row in enumerate(T[10].rows[3:7], start=1):
    set_cell(row.cells[2], "{pin%d_use}" % i)
    set_cell(row.cells[3], "{pin%d_color}" % i)

# ---- 11..15: photo pages -> one looping block ----
photo_tbls = T[11:16]
first = photo_tbls[0]
set_cell(first.rows[1].cells[0], "{p_desc}")
set_cell(first.rows[1].cells[1], "{p_file}")
del_rows_from(first, 2)


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
first._tbl.addprevious(text_para(model, "{#photos}", page_break=True))
first._tbl.addnext(text_para(model, "{/photos}"))

for tbl in photo_tbls[1:]:
    nxt = tbl._tbl.getnext()
    tbl._tbl.getparent().remove(tbl._tbl)
    while nxt is not None and nxt.tag == qn("w:p") and (nxt.text or "").strip() in (
            "", "DUPLICATE THIS PAGE AS MANY TIMES AS NEEDED"):
        rm = nxt
        nxt = nxt.getnext()
        rm.getparent().remove(rm)

doc.save(OUT)
print("wrote", OUT)
