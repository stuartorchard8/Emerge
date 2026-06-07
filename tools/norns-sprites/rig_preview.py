import sys, struct, zlib, math
sys.path.insert(0,'/home/stu/emerge/.build/norn-assets'); import s16
A="/home/stu/emerge/assets/norns"
def read_png(path):
    d=open(path,'rb').read(); o=8; W=H=0; idat=b''
    while o<len(d):
        ln=struct.unpack('>I',d[o:o+4])[0]; t=d[o+4:o+8]; data=d[o+8:o+8+ln]; o+=12+ln
        if t==b'IHDR': W,H=struct.unpack('>II',data[:8])
        elif t==b'IDAT': idat+=data
        elif t==b'IEND': break
    raw=zlib.decompress(idat); px=[None]*(W*H); i=0
    for y in range(H):
        i+=1
        for x in range(W):
            r,g,b,a=raw[i],raw[i+1],raw[i+2],raw[i+3]; i+=4
            px[y*W+x]=(r,g,b) if a>0 else None
    return W,H,px
parts={}
for line in open(f"{A}/denali_rig.txt"):
    tok=line.split()
    if len(tok)<4: continue
    W,H,p=read_png(f"{A}/{tok[1]}"); pts={}
    for s in tok[4:]:
        k,xy=s.split(':'); x,y=xy.split(','); pts[k]=(float(x),float(y))
    parts[tok[0]]={"W":W,"H":H,"px":p,"pts":pts}
def matmul(m,n):
    a,b,c,d,e,f=m; A_,B_,C_,D_,E_,F_=n
    return (a*A_+c*B_,b*A_+d*B_,a*C_+c*D_,b*C_+d*D_,a*E_+c*F_+e,b*E_+d*F_+f)
def T(x,y): return (1,0,0,1,x,y)
def R(t): c=math.cos(t);s=math.sin(t); return (c,s,-s,c,0,0)
def Sc(sx,sy): return (sx,0,0,sy,0,0)
def apply(m,x,y): a,b,c,d,e,f=m; return (a*x+c*y+e,b*x+d*y+f)
def place(jx,jy,part,rot):
    st=part["pts"]["start"]; en=part["pts"]["end"]
    t=matmul(T(jx,jy), matmul(R(rot), T(-st[0],-st[1])))
    tx,ty=apply(t,en[0],en[1]); return t,tx,ty
def build(phase, walk):
    s=math.sin(phase); body=parts["body"]; hip=body["pts"]
    hs=(0.4 if walk else 0.0); aw=(0.5 if walk else 0.0)
    placed=[]
    def leg(hipk,th,sh,ft,sgn):
        r0=sgn*s*hs
        t,tx,ty=place(*hip[hipk],parts[th],r0); placed.append((th,t))
        r1=r0+0.10+max(0.0,sgn*s)*0.25
        t,tx,ty=place(tx,ty,parts[sh],r1); placed.append((sh,t))
        t,_,_=place(tx,ty,parts[ft],r1*0.5); placed.append((ft,t))
    def arm(shk,ua,fa,sgn):
        r0=sgn*s*aw
        t,tx,ty=place(*hip[shk],parts[ua],r0); placed.append((ua,t))
        t,_,_=place(tx,ty,parts[fa],r0+0.1); placed.append((fa,t))
    leg("hipL","thighL","shinL","footL",+1)   # far
    arm("shL","uarmL","farmL",-1)
    placed.append(("body",T(0,0)))
    leg("hipR","thighR","shinR","footR",-1)    # near
    arm("shR","uarmR","farmR",+1)
    hp=hip["head"]; neck=parts["head"]["pts"]["neck"]; placed.append(("head",matmul(T(hp[0],hp[1]+11),T(-neck[0],-neck[1]))))
    return placed
def render(phase,walk,out,scale=4):
    placed=build(phase,walk); OW=380;OH=420; g=matmul(T(190,300), matmul(Sc(scale,scale), T(-5,-20)))
    canvas=[None]*(OW*OH)
    for name,t in placed:
        part=parts[name]; full=matmul(g,t); a,b,c,d,e,f=full; W,H,px=part["W"],part["H"],part["px"]
        for y in range(H):
            for x in range(W):
                p=px[y*W+x]
                if p is None: continue
                ox=int(round(a*x+c*y+e)); oy=int(round(b*x+d*y+f))
                for dx in range(scale+1):
                    for dy in range(scale+1):
                        X=ox+dx;Y=oy+dy
                        if 0<=X<OW and 0<=Y<OH: canvas[Y*OW+X]=p
    buf=bytearray(bytes((46,46,52))*(OW*OH))
    for i,p in enumerate(canvas):
        if p: buf[i*3:i*3+3]=bytes(p)
    s16.write_png(out,OW,OH,bytes(buf)); print("->",out.split('/')[-1])
render(0.0, False, "/home/stu/emerge/.build/norn-assets/rigprev_stand.png")
render(1.2, True,  "/home/stu/emerge/.build/norn-assets/rigprev_walk.png")
