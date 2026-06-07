# Norn sprite pipeline (placeholder C2 art)

Rips a Creatures-2 breed, decodes its sprites, and bakes side-view **pose sheets** for the engine.
Placeholder until original art replaces it. Raw breed files live in `.build/norn-assets/` (gitignored);
the baked sheets (`assets/norns/<breed>_a<age>.png` + `<breed>.json`) are committed.

## Rip + decode (one-time, raw files into `.build/norn-assets/`)
1. **Download** a breed zip from the Eem Foo archive (needs a browser UA + cookie jar + Referer and
   `?download=true&original=1`). The zip contains a Windows installer `.exe`.
2. **`scan_deflate.py <installer.exe> <outdir>`** — the installer payload is one zlib stream; extract it.
3. **`extract_archive.py <payload.bin> <sprites_dir>`** — the payload is a custom archive of
   `[u32 nameLen][name][u32 fileSize][bytes]` records; pulls out the `.s16` + `.att` files.
4. **`s16.py`** — decodes Creatures S16 (RGB555/565, 0x0000=transparent) → PNG (pure stdlib, no Pillow).

## Bake (run whenever regenerating assets)
**`bake_breed.py`** composites the parts into per-age pose sheets and writes them into `assets/norns/`.

## The Creatures rig (what the bake encodes)
- Parts: `a`=head `b`=body `c·d·e`/`f·g·h`=legs (thigh·shin·foot) `i·j`/`k·l`=arms (upper·fore) `m·n`=tail.
- `.att` per part = one row per **pose** (C1/C2 = 10 poses): body row = 6 points (head, legL, legR,
  armL, armR, tail); head row = neck + mouth; limbs = start + end.
- **Poses (the key insight):** 0–3 = facing RIGHT at four body pitches (**0 = crawl/head-down,
  1 = walk-lean, 2 = upright stand, 3 = reach-up**); 4–7 = facing LEFT; 8 = front; 9 = back.
- Assemble at a **single consistent pose index** for every part (head frame index == pose index,
  head neck == head `.att` row), chaining limbs via their start/end points. No rotation/FK — Creatures
  animates by cycling whole-body poses. The engine (`NornRig.kt`) flips horizontally for facing,
  cycles poses for the walk/crawl stride, and scales by life stage. Babies/children use the low
  (crawl) poses; older Norns stand upright.
