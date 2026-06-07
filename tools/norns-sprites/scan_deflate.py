import sys, zlib, os
path = sys.argv[1]; outdir = sys.argv[2]
os.makedirs(outdir, exist_ok=True)
data = open(path,'rb').read()
print(f"file size {len(data)}")
heads = [b'\x78\x01', b'\x78\x9c', b'\x78\xda']
found = []
i = 0
n = 0
while i < len(data)-2:
    if data[i:i+2] in heads:
        try:
            d = zlib.decompressobj()
            out = d.decompress(data[i:], 8_000_000)
            if len(out) >= 256:
                found.append((i, len(out), out[:4]))
                if n < 60:
                    open(os.path.join(outdir,f"blob_{i}.bin"),'wb').write(out)
                n += 1
                # skip past consumed input
                consumed = len(data[i:]) - len(d.unused_data)
                i += max(1, consumed)
                continue
        except Exception:
            pass
    i += 1
print(f"deflate blobs found: {len(found)} (saved up to 60)")
found.sort(key=lambda t:-t[1])
for off,sz,h in found[:25]:
    print(f"  off={off:>9} size={sz:>9} head={h.hex()}")
