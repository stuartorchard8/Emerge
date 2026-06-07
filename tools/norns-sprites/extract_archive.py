import sys, os, re
b = open(sys.argv[1],'rb').read(); outdir = sys.argv[2]; os.makedirs(outdir, exist_ok=True)
def u32(o): return int.from_bytes(b[o:o+4],'little')
# find first record: a u32 namelen immediately followed by a name ending in .s16/.att/.c16
m = re.search(rb'[A-Za-z0-9_ -]{2,20}\.(s16|att|c16)', b)
start = m.start()-4
assert u32(start)==len(m.group()), (u32(start), m.group())
o=start; recs=[]
while o+8 <= len(b):
    nl=u32(o)
    if nl<1 or nl>64: break
    name=b[o+4:o+4+nl]
    if not re.fullmatch(rb'[ -~]+', name): break
    fo=o+4+nl; fs=u32(fo)
    if fo+4+fs>len(b): break
    recs.append((name.decode('latin1'), fo+4, fs)); o=fo+4+fs
print(f"start={start} records={len(recs)} consumed_to={o}/{len(b)}")
saved=0
for name,off,sz in recs:
    if name.lower().endswith(('.s16','.c16','.att')):
        safe=os.path.basename(name.replace('\\','/')).strip()
        open(os.path.join(outdir,safe),'wb').write(b[off:off+sz]); saved+=1
exts={}
for n,_,_ in recs:
    e=n.lower().rsplit('.',1)[-1] if '.' in n else '(noext)'; exts[e]=exts.get(e,0)+1
print("saved:", saved, "ext counts:", dict(sorted(exts.items(),key=lambda x:-x[1])))
