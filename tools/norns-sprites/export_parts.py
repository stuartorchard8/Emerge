import sys, struct, zlib, json, os
sys.path.insert(0,'/home/stu/emerge/.build/norn-assets'); import s16
SRC="/home/stu/emerge/.build/norn-assets/denali_sprites"; OUT="/home/stu/emerge/assets/norns/parts"; AGE="2"; SLOT="v"
os.makedirs(OUT, exist_ok=True)
def att(L):
    rows=[]
    for ln in open(f"{SRC}/{L}0{AGE}{SLOT}.att").read().splitlines():
        n=[int(x) for x in ln.split()]
        if n: rows.append([(n[i],n[i+1]) for i in range(0,len(n)-1,2)])
    return rows
def write_rgba(path,w,h,pix):
    raw=bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p=pix[y*w+x]; raw+= bytes((p[0],p[1],p[2],255)) if p else bytes(4)
    def ch(t,d): return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    open(path,'wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack(">IIBBBBB",w,h,8,6,0,0,0))+ch(b'IDAT',zlib.compress(bytes(raw),9))+ch(b'IEND',b''))
def trim_and_save(L, fi, name, pts):
    imgs,_=s16.decode_s16(f"{SRC}/{L}0{AGE}{SLOT}.s16"); w,h,px=imgs[fi]
    xs=[x for y in range(h) for x in range(w) if px[y*w+x]]; ys=[y for y in range(h) for x in range(w) if px[y*w+x]]
    x0,y0,x1,y1=min(xs),min(ys),max(xs),max(ys); tw=x1-x0+1; th=y1-y0+1
    crop=[px[(y0+yy)*w+(x0+xx)] for yy in range(th) for xx in range(tw)]
    write_rgba(f"{OUT}/{name}.png", tw, th, crop)
    return {"img":f"parts/{name}.png","w":tw,"h":th, **{k:[v[0]-x0, v[1]-y0] for k,v in pts.items()}}
P=9; ba=att('b')[P]   # head,legL,legR,armL,armR,tail
rig={"frameAge":2,"breed":"denali","parts":{}}
# body: record its joint points (trimmed-local)
rig["parts"]["body"]=trim_and_save('b',P,"body",
    {"head":ba[0],"hipL":ba[1],"hipR":ba[2],"shL":ba[3],"shR":ba[4]})
# head frame 0 (right side). neck pivot = att start of head row 0 if sensible else bbox-bottom; use att[0][0]
ha=att('a'); 
rig["parts"]["head"]=trim_and_save('a',0,"head",{"neck":ha[0][0]})
# limb segments: start (proximal pivot) + end (distal tip) from att[P]
for L,name in [('c','thighL'),('d','shinL'),('e','footL'),('f','thighR'),('g','shinR'),('h','footR'),
               ('i','uarmL'),('j','farmL'),('k','uarmR'),('l','farmR')]:
    a=att(L)[P]; rig["parts"][name]=trim_and_save(L,P,name,{"start":a[0],"end":a[1] if len(a)>1 else a[0]})
json.dump(rig, open("/home/stu/emerge/assets/norns/denali_rig.json","w"), indent=1)
print("exported parts:", list(rig["parts"].keys()))
for k,v in rig["parts"].items(): print(f"  {k}: {v['w']}x{v['h']}  pts={ {kk:vv for kk,vv in v.items() if isinstance(vv,list)} }")
