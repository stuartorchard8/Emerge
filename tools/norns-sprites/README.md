# Norn sprite pipeline (placeholder C2 art)

Rips a Creatures-2 breed, decodes its sprites, and bakes a side-view sprite sheet for the engine.
Placeholder until original art replaces it. Scripts expect raw breed files in `.build/norn-assets/`.

1. **Download** a breed zip from the Eem Foo archive (needs a browser UA + cookie jar + Referer and
   `?download=true&original=1`). The zip contains a Windows installer `.exe`.
2. **`scan_deflate.py <installer.exe> <outdir>`** — the installer payload is one zlib stream; extract it.
3. **`extract_archive.py <payload.bin> <sprites_dir>`** — the payload is a custom archive of
   `[u32 nameLen][name][u32 fileSize][bytes]` records; pulls out the `.s16` + `.att` files.
4. **`s16.py`** — decodes Creatures S16 (RGB555/565, 0x0000=transparent) to PNG (pure stdlib).
5. **`compose_norn.py`** — composites parts (body pose 9 = side; limbs chained via `.att`; head
   placed by content-bbox on the neck point) into `assets/norns/<breed>.png` + `.json` (cell size,
   feet anchor, frame names). The Kotlin `NornSprites` loads these.

Parts: a=head b=body c·d·e/f·g·h=legs i·j/k·l=arms m·n=tail; ages 0–3; poses 0–3 front, 4–7 back, 8/9 sides.
