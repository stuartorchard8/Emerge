#!/usr/bin/env python3
"""Bake a Creatures-2 breed into per-age pose sheets for the engine.

For each life-stage age (0=baby .. 3=adult) and each side-facing pose
(0=crawl/down-pitch, 1=walk-lean, 2=upright stand, 3=reach-up), composite the
real C2 parts (body + legs + arms + head) via their ATT attachment points at a
SINGLE consistent pose index (head frame == pose index, head neck == head ATT
row), then pack the four poses into one transparent sheet. The engine cycles
poses for walk/crawl and flips horizontally for facing. No rotation/FK hacks —
this is how Creatures itself poses creatures.

Input : decoded .s16/.att in .build/norn-assets/denali_sprites/ (see README)
Output: assets/norns/<breed>_a<age>.png  + assets/norns/<breed>.json
"""
import sys, struct, zlib, json, os
sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, "/home/stu/emerge/.build/norn-assets")
import s16

SRC = "/home/stu/emerge/.build/norn-assets/denali_sprites"
OUT = "/home/stu/emerge/assets/norns"
BREED = "denali"; SLOT = "v"
POSES = [0, 1, 2, 3]                  # side-facing-right: crawl, walk-lean, stand, reach
ZORDER = "c d e i j b f g h k l a".split()   # far leg/arm, body, near leg/arm, head

def att(L, age):
    rows = []
    for ln in open(f"{SRC}/{L}0{age}{SLOT}.att").read().splitlines():
        n = [int(x) for x in ln.split()]
        if n: rows.append([(n[i], n[i + 1]) for i in range(0, len(n) - 1, 2)])
    return rows

def write_rgba(path, w, h, pix):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p = pix[y * w + x]; raw += bytes((p[0], p[1], p[2], 255)) if p else bytes(4)
    def ch(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                           + ch(b"IDAT", zlib.compress(bytes(raw), 9)) + ch(b"IEND", b""))

def assemble(frames, atts, P):
    ba = atts['b'][P]; placed = [('b', P, 0, 0)]
    def chain(start, segs):
        prev = tuple(start)
        for L in segs:
            a = atts[L][P]; o = (prev[0] - a[0][0], prev[1] - a[0][1]); placed.append((L, P, o[0], o[1]))
            prev = (o[0] + a[1][0], o[1] + a[1][1]) if len(a) > 1 else prev
    chain(ba[1], "cde"); chain(ba[2], "fgh"); chain(ba[3], "ij"); chain(ba[4], "kl")
    ha = atts['a'][P][0]; placed.append(('a', P, ba[0][0] - ha[0], ba[0][1] - ha[1]))
    placed.sort(key=lambda t: ZORDER.index(t[0]))
    minx = miny = 10**9; maxx = maxy = -10**9
    for L, fi, ox, oy in placed:
        w, h, _ = frames[L][fi]; minx = min(minx, ox); miny = min(miny, oy); maxx = max(maxx, ox + w); maxy = max(maxy, oy + h)
    W = maxx - minx; H = maxy - miny; cv = [None] * (W * H)
    for L, fi, ox, oy in placed:
        w, h, px = frames[L][fi]
        for yy in range(h):
            for xx in range(w):
                p = px[yy * w + xx]
                if p is not None:
                    X = ox - minx + xx; Y = oy - miny + yy
                    if 0 <= X < W and 0 <= Y < H: cv[Y * W + X] = p
    return W, H, cv

manifest = {"breed": BREED, "poses": [f"p{P}" for P in POSES], "ages": {}}
for age in range(4):
    frames = {}; atts = {}
    for L in "abcdefghijkl": frames[L], _ = s16.decode_s16(f"{SRC}/{L}0{age}{SLOT}.s16"); atts[L] = att(L, age)
    rs = [assemble(frames, atts, P) for P in POSES]
    cellW = max(w for w, _, _ in rs) + 6; cellH = max(h for _, h, _ in rs) + 6
    anchorX = cellW // 2; anchorY = cellH - 3
    sheetW = cellW * len(rs); sheet = [None] * (sheetW * cellH)
    for idx, (W, H, cv) in enumerate(rs):
        xs = [x for y in range(H) for x in range(W) if cv[y * W + x]]
        ys = [y for y in range(H) for x in range(W) if cv[y * W + x]]
        cx = (min(xs) + max(xs)) // 2; fy = max(ys)
        ox = idx * cellW + anchorX - cx; oy = anchorY - fy
        for y in range(H):
            for x in range(W):
                p = cv[y * W + x]
                if p:
                    X = ox + x; Y = oy + y
                    if 0 <= X < sheetW and 0 <= Y < cellH: sheet[Y * sheetW + X] = p
    os.makedirs(OUT, exist_ok=True)
    write_rgba(f"{OUT}/{BREED}_a{age}.png", sheetW, cellH, sheet)
    manifest["ages"][str(age)] = {"sheet": f"{BREED}_a{age}.png", "cellW": cellW, "cellH": cellH, "anchorX": anchorX, "anchorY": anchorY}
    print(f"age {age}: sheet {sheetW}x{cellH} cell {cellW}x{cellH}")
json.dump(manifest, open(f"{OUT}/{BREED}.json", "w"), indent=1)
print("wrote", f"{OUT}/{BREED}.json")
