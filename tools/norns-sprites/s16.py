import sys, struct, zlib, math, os
def decode_s16(path):
    b=open(path,'rb').read(); flags=int.from_bytes(b[0:4],'little'); cnt=int.from_bytes(b[4:6],'little')
    is565=bool(flags&1); imgs=[]; o=6
    for _ in range(cnt):
        off=int.from_bytes(b[o:o+4],'little'); w=int.from_bytes(b[o+4:o+6],'little'); h=int.from_bytes(b[o+6:o+8],'little'); o+=8
        px=[]
        for i in range(w*h):
            v=int.from_bytes(b[off+i*2:off+i*2+2],'little')
            if v==0: px.append(None)
            elif is565: px.append((((v>>11)&31)<<3,((v>>5)&63)<<2,(v&31)<<3))
            else:       px.append((((v>>10)&31)<<3,((v>>5)&31)<<3,(v&31)<<3))
        imgs.append((w,h,px))
    return imgs,is565
def write_png(path,w,h,rgb):
    def ch(t,d): return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    raw=bytearray()
    for y in range(h): raw.append(0); raw+=rgb[y*w*3:(y+1)*w*3]
    open(path,'wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack(">IIBBBBB",w,h,8,2,0,0,0))+ch(b'IDAT',zlib.compress(bytes(raw),9))+ch(b'IEND',b''))
def zoom(path,out,indices,scale=6,cols=8):
    imgs,_=decode_s16(path); sel=[imgs[i] for i in indices if i<len(imgs)]
    cw=(max(w for w,_,_ in sel)+2)*scale; chh=(max(h for _,h,_ in sel)+2)*scale
    rows=(len(sel)+cols-1)//cols; W=cols*cw; Hh=rows*chh; buf=bytearray(bytes((28,28,32))*(W*Hh))
    def put(x,y,c):
        if 0<=x<W and 0<=y<Hh: i=(y*W+x)*3; buf[i:i+3]=bytes(c)
    for idx,(w,h,px) in enumerate(sel):
        ox=(idx%cols)*cw+scale; oy=(idx//cols)*chh+scale
        for yy in range(h):
            for xx in range(w):
                p=px[yy*w+xx]; c=p if p else (70,70,80)
                for dy in range(scale):
                    for dx in range(scale): put(ox+xx*scale+dx,oy+yy*scale+dy,c)
    write_png(out,W,Hh,bytes(buf)); print(f"zoom {os.path.basename(path)} {len(sel)} -> {out} {W}x{Hh}")
if __name__=='__main__':
    for f in sys.argv[1:]: 
        imgs,is565=decode_s16(f); print(f, len(imgs),'frames 565=',is565)
