import sys, struct, zlib, json, os
sys.path.insert(0,'/home/stu/emerge/.build/norn-assets'); import s16
SRC="/home/stu/emerge/.build/norn-assets/denali_sprites"; OUT="/home/stu/emerge/assets/norns"
AGE="2"; SLOT="v"; HEADFRAME=0
def att(L):
    rows=[]
    for ln in open(f"{SRC}/{L}0{AGE}{SLOT}.att").read().splitlines():
        n=[int(x) for x in ln.split()]
        if n: rows.append([(n[i],n[i+1]) for i in range(0,len(n)-1,2)])
    return rows
frames={};atts={}
for L in "abcdefghijklmn": frames[L],_=s16.decode_s16(f"{SRC}/{L}0{AGE}{SLOT}.s16"); atts[L]=att(L)
def bbox(L,fi):
    w,h,px=frames[L][fi];xs=[];ys=[]
    for y in range(h):
        for x in range(w):
            if px[y*w+x] is not None: xs.append(x);ys.append(y)
    return min(xs),min(ys),max(xs),max(ys)
ORDER='m n c d e i j b f g h k l a'.split()
def assemble(P, legLdx, legRdx, legRdy, bodydy):
    ba=atts['b'][P]; placed=[('b',P,0,bodydy)]
    def chain(start, segs, dx, dy):
        prev=(start[0]+dx, start[1]+dy)
        for L in segs:
            a=atts[L][P]; o=(prev[0]-a[0][0], prev[1]-a[0][1]); placed.append((L,P,o[0],o[1]))
            prev=(o[0]+a[1][0], o[1]+a[1][1]) if len(a)>1 else (o[0],o[1])
    chain(ba[1],"cde",legLdx,0); chain(ba[2],"fgh",legRdx,legRdy)
    chain(ba[3],"ij",0,bodydy); chain(ba[4],"kl",0,bodydy); chain(ba[5],"mn",0,bodydy)
    bx0,by0,bx1,by1=bbox('a',HEADFRAME); hcx=(bx0+bx1)//2; hcy=by1
    neck=ba[0]; placed.append(('a',HEADFRAME, neck[0]-hcx, neck[1]-hcy+16+bodydy))
    return placed
def rasterize(placed):
    placed=sorted(placed,key=lambda t:ORDER.index(t[0]))
    minx=miny=10**9;maxx=maxy=-10**9
    for L,fi,ox,oy in placed:
        w,h,_=frames[L][fi];minx=min(minx,ox);miny=min(miny,oy);maxx=max(maxx,ox+w);maxy=max(maxy,oy+h)
    W=maxx-minx;H=maxy-miny; cv=[None]*(W*H)
    for L,fi,ox,oy in placed:
        w,h,px=frames[L][fi]
        for yy in range(h):
            for xx in range(w):
                p=px[yy*w+xx]
                if p is not None:
                    X=ox-minx+xx;Y=oy-miny+yy
                    if 0<=X<W and 0<=Y<H: cv[Y*W+X]=p
    return W,H,cv
# frames: stand, walk0, walk1  (P=9 side-right)
specs={"stand":(9,0,0,0,0),"walk0":(9,3,-3,-2,1),"walk1":(9,-3,3,-2,1)}
rasters={k:rasterize(assemble(*v)) for k,v in specs.items()}
# common cell, align feet (content bottom-centre) to a fixed anchor
cellW=max(W for W,_,_ in rasters.values())+8
cellH=max(H for _,H,_ in rasters.values())+8
anchorX=cellW//2; anchorY=cellH-3
names=list(specs.keys())
sheetW=cellW*len(names); sheetH=cellH
sheet=[None]*(sheetW*sheetH)
for idx,nm in enumerate(names):
    W,H,cv=rasters[nm]
    # content bbox within cv
    xs=[x for y in range(H) for x in range(W) if cv[y*W+x]]; ys=[y for y in range(H) for x in range(W) if cv[y*W+x]]
    cx=(min(xs)+max(xs))//2; fy=max(ys)
    ox=idx*cellW + anchorX - cx; oy=anchorY - fy
    for y in range(H):
        for x in range(W):
            p=cv[y*W+x]
            if p:
                X=ox+x;Y=oy+y
                if 0<=X<sheetW and 0<=Y<sheetH: sheet[Y*sheetW+X]=p
# write RGBA PNG
def write_rgba(path,w,h,pix):
    raw=bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p=pix[y*w+x]
            raw+= bytes((p[0],p[1],p[2],255)) if p else bytes(4)
    def ch(t,d): return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    open(path,'wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack(">IIBBBBB",w,h,8,6,0,0,0))+ch(b'IDAT',zlib.compress(bytes(raw),9))+ch(b'IEND',b''))
os.makedirs(OUT,exist_ok=True)
write_rgba(f"{OUT}/denali.png", sheetW, sheetH, sheet)
json.dump({"breed":"denali","cellW":cellW,"cellH":cellH,"anchorX":anchorX,"anchorY":anchorY,"frames":names},
          open(f"{OUT}/denali.json","w"), indent=2)
print(f"sheet {sheetW}x{sheetH}, cell {cellW}x{cellH}, anchor ({anchorX},{anchorY}), frames {names}")
