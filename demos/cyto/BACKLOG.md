# Cyto backlog

Queued work + known artifacts, so we don't lose track. (Design rationale for the energy-economy thread
lives in the conversation history; MORPHOGENESIS.md is the source of truth for the model.)

## Recently landed (context)

- **Metabolic leak** — passive exchange leaks only species the cell *can't* metabolise; usable matter is
  retained, so cells can hoard an imported reserve. Food web now via death + degradation.
- **Active-uptake gradient cost** — Import yield `⌊k·SCALE/(SCALE + max(0, cyto−env))⌋`
  (`IMPORT_GRADIENT_SCALE`): 1:1 at/below ambient, diminishing as the cell concentrates above it. Hoarding
  self-limits (soft cap); nutrient-poor patches become an energy-rich niche; competition rewards
  over-pushing before the cheap band depletes. (Passive exchange + synthesis unchanged.)
- **Cell panel** — env|CYT|BIO metabolism table with two flow arrows; reservoir shown; FormBond displayed
  by its two operative atoms.
- **Sim/draw decoupled** (own sim thread + speed control); **in-game gene-editor** (tap a gene → edit /
  dup / delete, dropdowns + hold-to-repeat).

## Queued — energy economy (Stu's order)

The guiding principle: **too many things are hard-coded engine rules that should be genetic choices; the
blocker is that actions cost too much, so cheap-enough actions is the unlock.** The gradient-cost above is
the first step (efficient-slow is the free default; over-push only when contested).

1. **Base-efficiency gene field** — an explicit evolvable trait so a cell can *choose* its rate↔efficiency
   point per gene (not just ride the gradient). Must be a trade-off (not a monotone dial like raw potency),
   e.g. discrete gears where high gear = more output per energy but a lower ops/tick rate cap — optimum is
   niche-dependent (energy-rich → fast/wasteful, energy-poor → slow/efficient). Stu wants this.
2. **Sensed-vs-metabolic split** — a `ChemQty` *sense*-gate currently makes the sensed species handleable →
   retained → the cell hoards a signal it only senses (e.g. cell 1403 hoards `c` from a `ChemQty cc` gate).
   Split "metabolic reach" (retain) from "sensed-only" (still absorbable to sense, but leaks so it tracks
   the reservoir). Proper fix for the hoarding-a-signal artifact.
3. **Gradient cost on synthesis** (Convert / FormBond) — only if synthesis turns out to need the same
   diminishing-returns treatment; currently flat.
4. **Cytoplasm capacity = f(biomass), overflow leaks** — Stu's idea; *likely redundant* now that the
   gradient cost gives an emergent soft cap. Revisit only if a hard-ish capacity still feels wanted.

Rejected: per-resource energy sharing (divide light only among light genes) — keep the genome-bloat tax;
it drives the solar-vs-chem divergence.

## Known artifacts (not bugs, watch / maybe address)

- **Lex-smallest fragment selection** biases FormBond toward small molecules — a cell can't reliably build a
  specific larger molecule (e.g. ABBA) while smaller fragments are present. Alt rule (longest-match /
  round-robin) would re-baseline; left as-is for now.
- Both cell-panel flow arrows are *predicted* from genome + state, not measured per-tick deltas (fine for
  the steady story; a gated-off gene still shows its intent).

## Tooling / interaction (next focus)

Stu wants to improve his ability to **test & interact with the world**. _(to be filled in)_
