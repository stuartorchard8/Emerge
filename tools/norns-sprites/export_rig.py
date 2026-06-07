#!/usr/bin/env python3
"""Export a breed's per-age Norn parts (at each age's base pose) + rig manifest, AUTO-DETECTING the
breed's naming scheme (sex digit / breed-slot / age digits all vary per breed). Usage:
    export_rig.py <breed> <srcDir>
Picks the breed slot + the sex with the most COMPLETE ages (all 14 parts a-n present), maps the
available ages to the four life-stage indices (baby/child/adolescent/adult; repeats the last if
fewer), and bakes parts/<breed>/a<idx>/ + <breed>_rig_a<idx>.txt.
"""
import sys, os, re, glob, struct, zlib
sys.path.insert(0, "/home/stu/emerge/.build/norn-assets"); import s16
BREED = sys.argv[1]; SRC = f"/home/stu/emerge/.build/norn-assets/{sys.argv[2]}"
PARTS = "abcdefghijklmn"
rx = re.compile(r'^([a-n])(\d)(\d)(.)\.s16$')
present = {}  # (slot,sex,age) -> set(parts)
for p in glob.glob(f"{SRC}/*.s16"):
    m = rx.match(os.path.basename(p))
    if not m: continue
    part, sex, age, slot = m.group(1), m.group(2), m.group(3), m.group(4)
    if os.path.exists(p[:-4] + ".att"):
        present.setdefault((slot, sex, age), set()).add(part)
# choose slot = the one with the most complete (sex,age) sets
from collections import Counter
slotScore = Counter()
for (slot, sex, age), parts in present.items():
    if len(parts) == 14: slotScore[slot] += 1
if not slotScore: sys.exit(f"{BREED}: no complete (14-part) age found")
SLOT = slotScore.most_common(1)[0][0]
# choose sex with the most complete ages under that slot
sexAges = {}
for (slot, sex, age), parts in present.items():
    if slot == SLOT and len(parts) == 14: sexAges.setdefault(sex, set()).add(age)
SEX = max(sexAges, key=lambda s: len(sexAges[s]))
ages = sorted(sexAges[SEX])
AGE_DIGITS = [ages[min(i, len(ages) - 1)] for i in range(4)]
print(f"{BREED}: slot={SLOT} sex={SEX} fullAges={ages} -> {AGE_DIGITS}")

def fn(L, i, ext): return f"{SRC}/{L}{SEX}{AGE_DIGITS[i]}{SLOT}.{ext}"
def att(L, i):
    rows = []
    for ln in open(fn(L, i, "att")).read().splitlines():
        n = [int(x) for x in ln.split()]
        if n: rows.append([(n[k], n[k+1]) for k in range(0, len(n)-1, 2)])
    return rows
def write_rgba(path, w, h, pix):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p = pix[y*w+x]; raw += bytes((p[0],p[1],p[2],255)) if p else bytes(4)
    def ch(t,d): return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    open(path,"wb").write(b"\x89PNG\r\n\x1a\n"+ch(b"IHDR",struct.pack(">IIBBBBB",w,h,8,6,0,0,0))+ch(b"IDAT",zlib.compress(bytes(raw),9))+ch(b"IEND",b""))
def export(i):
    P = 0 if i <= 1 else 2
    OUT = f"/home/stu/emerge/assets/norns/parts/{BREED}/a{i}"; os.makedirs(OUT, exist_ok=True)
    def save(L, fi, name, pts):
        imgs,_ = s16.decode_s16(fn(L, i, "s16")); w,h,px = imgs[fi]
        xs=[x for y in range(h) for x in range(w) if px[y*w+x]]; ys=[y for y in range(h) for x in range(w) if px[y*w+x]]
        x0,y0,x1,y1=min(xs),min(ys),max(xs),max(ys); tw=x1-x0+1; th=y1-y0+1
        crop=[px[(y0+yy)*w+(x0+xx)] for yy in range(th) for xx in range(tw)]
        write_rgba(f"{OUT}/{name}.png", tw, th, crop)
        return tw, th, {k:(v[0]-x0, v[1]-y0) for k,v in pts.items()}
    ba = att('b', i)[P]; rows=[]
    def line(name, L, fi, pts):
        tw,th,pp = save(L,fi,name,pts); s=" ".join(f"{k}:{v[0]},{v[1]}" for k,v in pp.items())
        rows.append(f"{name} parts/{BREED}/a{i}/{name}.png {tw} {th} {s}")
    line("body",'b',P,{"head":ba[0],"hipL":ba[1],"hipR":ba[2],"shL":ba[3],"shR":ba[4]})
    line("head",'a',P,{"neck":att('a',i)[P][0]})
    for L,name in [('c','thighL'),('d','shinL'),('e','footL'),('f','thighR'),('g','shinR'),('h','footR'),
                   ('i','uarmL'),('j','farmL'),('k','uarmR'),('l','farmR')]:
        a=att(L,i)[P]; line(name,L,P,{"start":a[0],"end":a[1] if len(a)>1 else a[0]})
    open(f"/home/stu/emerge/assets/norns/{BREED}_rig_a{i}.txt","w").write("\n".join(rows)+"\n")
for i in range(4): export(i)
print(f"{BREED}: baked 4 ages OK")
