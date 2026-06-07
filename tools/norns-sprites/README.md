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

## Export (run whenever regenerating assets)
**`export_rig.py`** exports, per life-stage age, the individual parts at that age's **base pose**
(older = upright side / pose 2; baby + child = crawl / pose 0), trimmed, with bone pivots from the
matching `.att` row → `assets/norns/parts/a<age>/*.png` + `assets/norns/denali_rig_a<age>.txt`.

## The Creatures rig (what the export encodes)
- Parts: `a`=head `b`=body `c·d·e`/`f·g·h`=legs (thigh·shin·foot) `i·j`/`k·l`=arms (upper·fore) `m·n`=tail.
- `.att` per part = one row per **pose** (C1/C2 = 10 poses): body row = 6 points (head, legL, legR,
  armL, armR, tail); head row = neck + mouth; limbs = start + end.
- **Poses (the key insight):** 0–3 = facing RIGHT at four body pitches (**0 = crawl/head-down,
  1 = walk-lean, 2 = upright stand, 3 = reach-up**); 4–7 = facing LEFT; 8 = front; 9 = back. Head
  frame index == pose index; head neck == head `.att` row for that pose.
- The engine (`NornRig.kt`) treats the parts as a **skeleton**: at rest they assemble exactly as the
  art intends (the head's neck on the body's neck via the real ATT points), and the joints get
  **continuous** swing for the walk — so motion interpolates smoothly rather than cutting between
  frames. Flips horizontally for facing, scales + picks the crawl/upright base by life stage.
