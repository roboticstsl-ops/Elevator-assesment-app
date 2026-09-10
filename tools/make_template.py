#!/usr/bin/env python3
"""Build the docxtemplater template from the client's <<token>> standard sheet.

Non-destructive: replaces sample text INSIDE existing runs so every font, colour,
tab stop, row height and table style from the original is kept. Only structural
work (loop rows for RF / photos) rewrites elements.

Input : DSO Tower 6_Templet.docx
Output: apk-build/www/template.docx
"""
import sys, re, copy
from docx import Document
from docx.oxml.ns import qn

SRC = sys.argv[1] if len(sys.argv) > 1 else "/Users/alihashi/Downloads/DSO Tower 6_Templet.docx"
OUT = sys.argv[2] if len(sys.argv) > 2 else "apk-build/www/template.docx"

doc = Document(SRC)
T = doc.tables


def replace_in_para(p, old, new):
    """Replace first occurrence of `old` across p's runs, keeping the first
    spanned run's formatting. Returns True if something was replaced."""
    runs = p.runs
    if not runs:
        return False
    full = "".join(r.text for r in runs)
    idx = full.find(old)
    if idx < 0:
        return False
    end = idx + len(old)
    # map char ranges to runs
    pos = 0
    first = None
    for ri, r in enumerate(runs):
        rlen = len(r.text)
        rstart, rend = pos, pos + rlen
        if rend <= idx or rstart >= end:
            pos = rend
            continue
        local_s = max(0, idx - rstart)
        local_e = min(rlen, end - rstart)
        if first is None:
            first = ri
            r.text = r.text[:local_s] + new + r.text[local_e:]
        else:
            r.text = r.text[:local_s] + r.text[local_e:]
        pos = rend
    return first is not None


def replace_everywhere(old, new, limit=None):
    n = 0
    for p in doc.paragraphs:
        while replace_in_para(p, old, new):
            n += 1
            if limit and n >= limit:
                return n
    for t in doc.tables:
        for row in t.rows:
            for c in row.cells:
                for p in c.paragraphs:
                    while replace_in_para(p, old, new):
                        n += 1
                        if limit and n >= limit:
                            return n
    return n


def cell_set(cell, text):
    """For originally-blank cells only: put one run with the paragraph's style."""
    p = cell.paragraphs[0]
    if p.runs:
        p.runs[0].text = text
        for r in p.runs[1:]:
            r.text = ""
    else:
        p.add_run(text)


def del_rows_from(tbl, start):
    for row in list(tbl.rows[start:]):
        tbl._tbl.remove(row._tr)


# ---- 1. plain <<token>> -> {tag} (formatting preserved in-run) ----
TOKENS = {
    "<<DocumentRef >>": "{doc_ref}", "<<DocumentRef>>": "{doc_ref}",
    "<<PreparedBy>>": "{prepared_by}", "<<Date>>": "{date}", "<<Version>>": "{version}",
    "<<ProjectActivity>>": "{project}", "<<SiteName>>": "{site_name}",
    "<<Client>>": "{client}", "<<FullAddress>>": "{address}", "<<GoogleMapPin>>": "{maps_link}",
    "<<ElevatorOEM>>": "{elev_oem}", "<<ElevatorModelSeries>>": "{elev_model}",
    "<<ElevatorID>>": "{elev_id}", "<<ElevatorMaintenanceCompany>>": "{elev_maint}",
    "<<AvailablePlugPoint(s)OnTopOfTheElevator>>": "{plug_points}",
    "<<Note>>": "{rail_note}",
    "<<du, e&>>": "{networks}", "<<Survey Point>>": "{survey_point}",
    "<<OverallFeasibility>>": "{feasibility}",
    "<<ButtonModuleSKU>>": "{btn_sku}",
    "<<ConnectorsUSedOnTheButtonModule>>": "{btn_connectors}",
}
for old, new in TOKENS.items():
    replace_everywhere(old, new)

# ---- 2. checklist: 13 x <<Choose>> -> {chk_0..12} positionally ----
n = 0
for tbl in (T[2], T[3]):
    for row in tbl.rows:
        for p in row.cells[1].paragraphs:
            if replace_in_para(p, "<<Choose>>", "{chk_%d}" % n):
                n += 1
                break
        else:
            # blank value cell fallback
            cell_set(row.cells[1], "{chk_%d}" % n); n += 1
assert n == 13, n

# checklist free-text note: append a paragraph after checklist table #3
note_after = T[3]._tbl.getnext()
if note_after is not None and note_after.tag == qn("w:p"):
    np = copy.deepcopy(note_after)
    for rr in list(np.findall(qn("w:r"))):
        np.remove(rr)
    run = np.makeelement(qn("w:r"), {})
    tn = np.makeelement(qn("w:t"), {})
    tn.set(qn("xml:space"), "preserve")
    tn.text = "{checklist_note}"
    run.append(tn)
    np.append(run)
    T[3]._tbl.addnext(np)

# ---- 3. railing measurement numbers (keep 'Measurements:' + labels + colours) ----
RAIL_SAMPLE = {
    "r_top":   [("A", "1190"), ("B", "1400"), ("C", "500"), ("D", "500"), ("E", "35"), ("F", "410"), ("G", "450")],
    "r_rear":  [("A", "1190"), ("B", "1110"), ("C", "505"), ("D", "505"), ("E", "35")],
    "r_left":  [("A", "1190"), ("B", "1110"), ("C", "35"), ("D", "280"), ("E", "505")],
    "r_right": [("A", "1190"), ("B", "1110"), ("C", "35"), ("D", "280"), ("E", "505")],
}
def fill_rail(paras, prefix):
    for L, val in RAIL_SAMPLE[prefix]:
        done = False
        for p in paras:
            # try "L: 1190mm" then "1190mm" then "1190"
            for pat in ("%s: %smm" % (L, val), "%smm" % val, val):
                repl = ("%s: {%s_%s}mm" % (L, prefix, L)) if pat.startswith(L + ":") else \
                       ("{%s_%s}mm" % (prefix, L)) if pat.endswith("mm") else \
                       ("{%s_%s}" % (prefix, L))
                if replace_in_para(p, pat, repl):
                    done = True
                    break
            if done:
                break

bp = doc.paragraphs
midx = [i for i, p in enumerate(bp) if p.text.strip().startswith("Measurements:")]
fill_rail([bp[midx[0]], bp[midx[0] + 1]], "r_top")
fill_rail([bp[midx[1]], bp[midx[1] + 1]], "r_rear")
for cell, prefix in ((T[5].rows[0].cells[1], "r_left"), (T[5].rows[1].cells[1], "r_right")):
    ps = [p for p in cell.paragraphs if "Measurements:" in p.text or re.match(r"^[A-E]:", p.text.strip())]
    fill_rail(ps, prefix)

# ---- 4. RF tables -> one looping data row (formatting from the sample row) ----
RF_COLS = ["du_rsrp", "du_rsrq", "du_sinr", "du_band", "du_dl", "du_ul",
           "et_rsrp", "et_rsrq", "et_sinr", "et_band", "et_dl", "et_ul"]
RF_SAMPLE = {
    "rf1": ["Main lobby / reception", "-68", "-5", "30", "B3", "94.4", "53.4", "-64", "-3", "30", "B8", "203", "5.91"],
    "rf2": ["Ground", "-67", "-4", "29", "B3", "114", "52.1", "-69", "-4", "28", "B8", "23.5", "3.82"],
    "rf3": ["Ground", "-86", "-4", "16", "B3", "111", "13.6", "-97", "-4", "26", "B8", "73.4", "5.32"],
}
for tbl, tag in ((T[7], "rf1"), (T[8], "rf2"), (T[9], "rf3")):
    row = tbl.rows[2]
    samp = RF_SAMPLE[tag]
    # loc cell
    if not any(replace_in_para(p, samp[0], "{#%s}{loc}" % tag) for p in row.cells[0].paragraphs):
        cell_set(row.cells[0], "{#%s}{loc}" % tag)
    # 12 metric cells
    for ci, key in enumerate(RF_COLS, start=1):
        val = samp[ci]
        newtxt = "{%s}" % key + ("{/%s}" % tag if ci == 12 else "")
        cell = row.cells[ci]
        if not any(replace_in_para(p, val, newtxt) for p in cell.paragraphs):
            cell_set(cell, newtxt)
    del_rows_from(tbl, 3)

# ---- 4b. per-carrier averages tables (like the latest report) ----
AVG_HDR = ["Operator", "Avg RSRP (dBm)", "Avg RSRQ (dB)", "Avg SINR (dB)",
           "Avg DL (Mbps)", "Avg UL (Mbps)", "Avg Ping (ms)", "Avg Jitter (ms)", "Feasibility"]
AVG_KEYS = ["rsrp", "rsrq", "sinr", "dl", "ul", "ping", "jit", "feas"]

def add_avg_table(after_tbl, prefix):
    t = doc.add_table(rows=3, cols=9)
    try: t.style = T[6].style
    except Exception: pass
    for c, txt in zip(t.rows[0].cells, AVG_HDR):
        r = c.paragraphs[0].add_run(txt); r.bold = True
    for ri, (label, car) in enumerate([("Carrier 1 - du", "du"), ("Carrier 2 - e&", "et")], start=1):
        t.rows[ri].cells[0].paragraphs[0].add_run(label)
        for ci, key in enumerate(AVG_KEYS, start=1):
            t.rows[ri].cells[ci].paragraphs[0].add_run("{%s_%s_%s}" % (prefix, car, key))
    tbl_el = t._tbl
    tbl_el.getparent().remove(tbl_el)
    after_tbl._tbl.addnext(tbl_el)
    spacer = tbl_el.makeelement(qn("w:p"), {})
    tbl_el.addnext(spacer)

add_avg_table(T[9], "avg2")   # inside cabin block -> after 3.3
add_avg_table(T[6], "avg1")   # charging/lobby + on-cabin block -> after network table

# ---- 5. pinout USE / Color (rows 3..6, cols 2..3) ----
for i, row in enumerate(T[10].rows[3:7], start=1):
    if not any(row.cells[2].paragraphs and replace_in_para(p, "", "") for p in []):
        pass
    cell_set(row.cells[2], "{pin%d_use}" % i)
    # keep original colour word, but make it a placeholder so the app can override
    cur = row.cells[3].text.strip()
    if not (cur and replace_in_para(row.cells[3].paragraphs[0], cur, "{pin%d_color}" % i)):
        cell_set(row.cells[3], "{pin%d_color}" % i)

# ---- 6. photo pages -> one looping block, page break per photo ----
photo_tbls = T[11:16]
first = photo_tbls[0]
cell_set(first.rows[1].cells[0], "{p_desc}")
cell_set(first.rows[1].cells[1], "{p_file}")
del_rows_from(first, 2)

# page break before each generated photo table
pPr = first.rows[0].cells[0].paragraphs[0]._p.get_or_add_pPr()
if pPr.find(qn("w:pageBreakBefore")) is None:
    pPr.append(pPr.makeelement(qn("w:pageBreakBefore"), {}))

model = first._tbl.getprevious()
def tag_para(text):
    p = copy.deepcopy(model)
    for r in list(p.findall(qn("w:r"))):
        p.remove(r)
    run = p.makeelement(qn("w:r"), {})
    tn = p.makeelement(qn("w:t"), {})
    tn.set(qn("xml:space"), "preserve")
    tn.text = text
    run.append(tn)
    p.append(run)
    return p
first._tbl.addprevious(tag_para("{#photos}"))
first._tbl.addnext(tag_para("{/photos}"))

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
