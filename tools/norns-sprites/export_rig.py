#!/usr/bin/env python3
"""Export per-age Norn parts (at the age's base pose) + a rig manifest for the
articulated renderer. Older Norns use pose 2 (upright side); babies/children use
pose 0 (crawl). Each part is trimmed to content; pivots come from the .att row of
that pose (head neck = head .att[pose][0], matching the head sprite frame == pose).
Output: assets/norns/parts/a<age>/*.png  +  assets/norns/denali_rig_a<age>.txt
"""
import sys, struct, zlib, os
sys.path.insert(0, "/home/stu/emerge/.build/norn-assets"); import s16
SRC = "/home/stu/emerge/.build/norn-assets/denali_sprites"; SLOT = "v"
def att(L, age):
    rows = []
    for ln in open(f"{SRC}/{L}0{age}{SLOT}.att").read().splitlines():
        n = [int(x) for x in ln.split()]
        if n: rows.append([(n[i], n[i+1]) for i in range(0, len(n)-1, 2)])
    return rows
def write_rgba(path, w, h, pix):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p = pix[y*w+x]; raw += bytes((p[0],p[1],p[2],255)) if p else bytes(4)
    def ch(t,d): return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    open(path,"wb").write(b"\x89PNG\r\n\x1a\n"+ch(b"IHDR",struct.pack(">IIBBBBB",w,h,8,6,0,0,0))+ch(b"IDAT",zlib.compress(bytes(raw),9))+ch(b"IEND",b""))
def export(age):
    P = 0 if age <= 1 else 2     # crawl base for young, upright for older
    OUT = f"/home/stu/emerge/assets/norns/parts/a{age}"; os.makedirs(OUT, exist_ok=True)
    def save(L, fi, name, pts):
        imgs,_ = s16.decode_s16(f"{SRC}/{L}0{age}{SLOT}.s16"); w,h,px = imgs[fi]
        xs=[x for y in range(h) for x in range(w) if px[y*w+x]]; ys=[y for y in range(h) for x in range(w) if px[y*w+x]]
        x0,y0,x1,y1=min(xs),min(ys),max(xs),max(ys); tw=x1-x0+1; th=y1-y0+1
        crop=[px[(y0+yy)*w+(x0+xx)] for yy in range(th) for xx in range(tw)]
        write_rgba(f"{OUT}/{name}.png", tw, th, crop)
        return tw, th, {k:(v[0]-x0, v[1]-y0) for k,v in pts.items()}
    ba = att('b', age)[P]; rows=[]
    def line(name, L, fi, pts):
        tw,th,pp = save(L,fi,name,pts); s=" ".join(f"{k}:{v[0]},{v[1]}" for k,v in pp.items())
        rows.append(f"{name} parts/a{age}/{name}.png {tw} {th} {s}")
    line("body",'b',P,{"head":ba[0],"hipL":ba[1],"hipR":ba[2],"shL":ba[3],"shR":ba[4]})
    line("head",'a',P,{"neck":att('a',age)[P][0]})
    for L,name in [('c','thighL'),('d','shinL'),('e','footL'),('f','thighR'),('g','shinR'),('h','footR'),
                   ('i','uarmL'),('j','farmL'),('k','uarmR'),('l','farmR')]:
        a=att(L,age)[P]; line(name,L,P,{"start":a[0],"end":a[1] if len(a)>1 else a[0]})
    open(f"/home/stu/emerge/assets/norns/denali_rig_a{age}.txt","w").write("\n".join(rows)+"\n")
    print(f"age {age} (pose {P}): {len(rows)} parts")
for a in range(4): export(a)
