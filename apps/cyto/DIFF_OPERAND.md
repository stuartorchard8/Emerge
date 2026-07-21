# The difference operand — parked

**Status:** deferred, 2026-07-22. Not started. This is a shelf doc, not a work order — it exists so a
future return can re-assess priority quickly without re-deriving the reasoning.

The two other changes that used to share a plan with this one have **landed**:

- **Int-kind operand dispatch** (`Operand.kind`, `@JvmField` on the base class) — done.
- **Dropping `CONC`** (genome v4, migrated to a raw count; three non-viable genomes deleted) — done.

So the dispatch groundwork `Diff` depends on already exists. What remains is only `Diff` itself.

---

## Why we diverted away

Not because `Diff` is hard — it's cheap (see §Cost). Because a design conversation reframed what cyto
actually needs right now, and `Diff` isn't it.

The frame: three objectives in tension — **sim speed, rule simplicity, emergent fertility** — with
Conway's Game of Life as the gold standard (simplicity as the gateway to speed, fertility "high enough"
to stay interesting). Working that through against cyto's actual architecture produced three conclusions
that together de-prioritise `Diff`:

1. **Cyto is not on the Conway axis.** In Conway, rule simplicity *is* speed — one fixed stencil on a
   uniform lattice. In cyto the speed cost lives in the **substrate** (continuous off-lattice physics,
   the matter field, diffusion, per-cell variable-length programs), not in the gate language. Biology is
   genome-volume-bound and code micro-opts are exhausted; the levers that moved TPS were broadphase and
   exchange, never the operand set. So no change to the condition language — `Diff` included — buys
   meaningful speed.

2. **The fertility bottleneck is almost certainly not the gene language.** `Diff` is a gene-*expression*
   change: it lets a gene sense something it couldn't. But cyto's live fertility problems are elsewhere —
   the swimmer is non-viable post-inversion, the ecology is boom-bust (~4400 cells then collapse), the
   campaign genomes behave differently than authored. Those are **substrate and selection-pressure**
   problems. The working hypothesis (see §Open question) is that cyto is **selection-limited, not
   expression-limited**: genomes can already express far more behaviour than the ecology stably rewards.
   If that holds, richer sensing polishes the wrong surface.

3. **The immediate priority is rebuilding viability after the chemical-energy inversion** — getting
   organisms that live, persist, and are selected for, in the inverted thermodynamics. Until that exists,
   there is no healthy organism whose behaviour `Diff` would improve, and no clean signal to A/B a new
   operand against. (This is the same reason the swimmer couldn't anchor the `CONC` migration — a dead
   organism has no measurable steady state.)

`Diff` is a good idea whose value is **contingent on a working ecology that doesn't exist yet**. Come
back to it once viability is restored.

---

## What `Diff` is

```kotlin
data class Diff(val a: Operand, val b: Operand) : Operand(OperandKind.DIFF)   // evaluates to a - b
```

**Bounded to exactly one level: `a` and `b` must be leaves** (`Constant`/`Chem`/`Biomass`/`Touching`/
`Neighbours`), never another `Diff`. This is a permanent constraint, not a first step — see §The bound.

A gene could then gate on `A - B` against a threshold: "WHEN RG MINUS GB > 100".

## What `Diff` gives

- **A signed relationship between two live quantities against an arbitrary threshold.** Today both sides
  of a clause are operands, so `RG > GB` already tests one quantity against another — but only at the
  crossover point (`k = 0`). `Diff` adds the *offset*: `RG - GB > 100` ("rg leads gb by more than 100").
  That is genuinely inexpressible now.
- **A difference is not a ratio, and gene duplication can't build it.** This is the honest case for a new
  operand: unlike OR (two genes cover it) or the `k=1` ratio (`A > B` covers it), no combination of
  existing genes constructs the value `a - b`. If you need a signed gap with an offset, you need this.
- **It reads as a sentence.** "RG MINUS GB > 100" survives the progressive-disclosure gene UI. That is
  the interpretability bar every gene-language change has to clear, and `Diff` clears it — *at one level*.

## What `Diff` does NOT give / does not fix

- **No speed.** Neutral-to-slightly-negative on TPS (§Cost). It is not a performance play.
- **No fertility, if the selection-limited hypothesis holds.** It widens the space of expressible
  organisms, but the current constraint is which organisms *persist and are rewarded*, not which are
  expressible. Widening expression under weak selection adds search-space, not emergence.
- **No help with the post-inversion viability problem.** That is substrate/selection work.
- **No tunable ratio.** `Diff` is a difference, not a ratio — it does not restore what `CONC` gave up
  (a size-normalised concentration with a tunable coefficient). Different operation, different use.

## The bound (why leaves-only is permanent)

Three reasons, in order of weight:

1. **The sentence UI.** "WHEN RG MINUS GB > 100" reads; nested arithmetic (`(RG - GB) - (BB - R)`) does
   not. Progressive disclosure is the sole gene UI on every width.
2. **Mutation space.** `mutateOperand` rolls a flat kind. Recursion lets mutation drift genomes into
   unreadable nested math with no natural bound — compare `GENOME_MAX_CLAUSES = 4`, which exists
   precisely to bound gate complexity.
3. **Performance.** A `Diff(Chem, Chem)` clause is up to **4 `cachedCount` linear scans** (two leaves,
   each up to two scans). Bounded and predictable *only* because leaves can't recurse; nesting makes the
   scan count unbounded.

## Cost when we do build it (small)

The dispatch groundwork is done, so `Diff` is now a contained addition:

- `OperandKind.DIFF = 5`, `COUNT = 6`; add the `Operand.Diff` subclass (leaves-only, enforced in the
  type or a construction check).
- One arm each in `CytoBiologyCore.operand` / `operandSnap` — evaluate `a - b`, both leaves. **Signed
  result**: the comparison must not assume non-negative operands. Plain `Int`, so `Frac`'s ~[-2,2] range
  is irrelevant; counts are bounded by `CELL_CHEM_CAP`, so `a - b` can't overflow.
- `selfGateCap`: a `Diff` clause imposes **no cap** (its value isn't linear in a single moving Q when
  both sides move) — the free-and-correct answer, exactly as `CONC` did.
- `mutateOperand`: `nextInt(5)` → `nextInt(6)`. Per the finding from the `CONC` work, this **does not
  re-route the PRNG stream** (one draw either way; only the mapping changes) — growth and interact
  goldens stay byte-identical, and even mutation-on may not drift. **`CytoMutationTest` is the real
  guard here, not the golden** — it asserts every `OperandKind` stays reachable and the `nextInt` bound
  matches the branch table. Update its expected `COUNT` and it will catch a misnumbering the golden
  sleeps through.
- `GeneCodec`: serialise/parse a `Diff` token (pick a syntax that reads, e.g. `rg-gb` or `Diff(rg,gb)`).
  No version bump needed for *adding* an operand (old genomes don't use it), but decide the token so it
  can't collide with a species or the `-` in a negative constant.
- UI: `GeneEditor` kind labels / index mapping / the L4 builder need a two-operand editor for the one
  nested level. This is the largest single piece of the work — a leaf picker for each side.
- Tests: extend `GeneCodecTest` round-trip coverage; add a `CytoSoaSpecTest` case gating on a signed gap.

Sequencing rule still applies: land it against a green gate on its own commit, verify the population
trajectory before any re-baseline.

## Open question that decides priority (resolve before building)

**Is cyto's emergence expression-limited or selection-limited?**

- If **expression-limited** (genomes bump against what the language can say): `Diff` climbs the list.
- If **selection-limited** (the language can already say more than the ecology rewards): `Diff` stays
  parked, and the work is in the substrate/selection regime instead.

Current belief: **selection-limited.** The cheap test, when someone wants to resolve it: put a complex
genome and a simple one in the same ecology and see whether complexity is ever rewarded / persists. If
complexity doesn't pay, no amount of new sensing helps — fix the payoff first.

## Relationship to the activation-curve fork

Separately on the table (not this doc): replacing the **boolean gate** with a graded **activation curve**
(realistically a piecewise-linear ramp, the only form that survives fixed-point + the sentence UI). That
is a different axis from `Diff` — it changes how sensing maps to *action*, not what can be *sensed*.
Notable interactions if that lands: a ramp may **subsume `selfGateCap`** (an action that tapers toward
its threshold instead of hard-stopping needs no sub-tick cap), and it is a **differentiation** bet, not
a fertility bet — possibly even a structural-fertility *cost* (crisp discrete states are where
Conway-style self-perpetuating structure lives). If the curve is ever adopted, revisit whether `Diff`'s
threshold semantics still make sense against a graded gate before building it.
