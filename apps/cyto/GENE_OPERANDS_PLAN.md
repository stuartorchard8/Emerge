# Gene operand cleanup + the difference operand

**Status:** proposed, 2026-07-21. Not started.

Two independent changes to the gene condition language, deliberately sequenced apart:

1. **Drop `CONC`** — an operand that reads as concentration but isn't one.
2. **Add a difference operand** — so a gene can gate on `A - B` against a threshold.

They are *not* the same change. `CONC` is a ratio (`count / biomass`); a difference is not a
ratio. Adding `DIFF` does not preserve `CONC`'s capability, and this plan does not pretend it
does — see §1.3 for what is actually being given up.

Overriding constraint for both: **no measurable TPS regression.** The gate path is the hottest
per-cell code in the sim, and §3 is the part of this document that matters most.

---

## 1. Drop CONC

### 1.1 Why

`Conc(sp) = count(sp) · CONC_SCALE / totalBiomass`. The name asserts a concentration, but
concentration needs a capacity denominator and biomass isn't one — cytoplasm capacity is
`CELL_CHEM_CAP`. Cytoplasm and biomass are independent pools that exchange molecules; neither
bounds the other. So the denominator is arbitrary.

It is also largely expressible already: because both sides of a clause are operands
(`CytoGenes.kt:92`), `RG > BIO` gives a chemistry-vs-body test with no special operand.

### 1.2 Current usage — real, and larger than a first grep suggests

Six saved genomes use it (an early grep of mine returned a false negative here; this list is
from `find -exec grep`):

| File | Use |
|---|---|
| `cyto-swimmer-v1.gene` | `Conc(gb) > 30` Convert, `> 20` Contract, `> 22` Divide |
| `cyto-swimmer-v0.gene` | same three |
| `cyto-jelly.gene` | `Conc(gb) > 30` Convert, `> 20` Contract |
| `cyto-genome-clock.gene` | `Conc(r) > 0` |
| `cyto-genome-simple-clock.gene` | `Conc(r) > 0` |
| `cyto-genome-self-start-clock.gene` | `Conc(r) > 0` |

No campaign genome, scenario, or snippet uses it. The three `Conc(r) > 0` uses are equivalent to
`r > 0` (any positive count over positive biomass floors above zero), so they migrate exactly.
Only the swimmer/jelly `Conc(gb) > k` uses carry real semantics.

### 1.3 What is genuinely lost

The only capability with no replacement is a **tunable ratio coefficient**. `RG > BIO` is the
ratio test at `k = 1` only; there is no multiplication in the operand language, so
"rg exceeds 3% of biomass" is inexpressible without `CONC`. Accepting this loss is a deliberate
design call, not an oversight.

Whether it is load-bearing in the swimmer is untested — the swimmer is believed regressed enough
that an A/B there wouldn't produce a clean signal, so this is being decided on design grounds.

### 1.4 The migration hazard — this is the one that bites

`GeneCodec.parseOperand` falls through to `Operand.Chem(untok(s))` for anything it doesn't
recognise. **Delete the `CONC` branch and `Conc(gb)` silently parses as a species named
`"Conc(gb)"`** — no error, no warning, a gene that simply never fires. The six genomes above
would load looking correct and behave wrong.

So removal *requires* an explicit migration, not a deletion:

- Bump the genome version (`# genome 3` → `4`) in `GeneCodec`.
- Add a `GenomeMigration` step rewriting `Conc(x) > k` → `x > k'` where `k'` is the constant
  re-derived against the genome's typical biomass, and `Conc(x) > 0` → `x > 0`.
- Keep a parse branch for `Conc(...)` that *errors loudly* rather than falling through to
  `Chem`, so any genome the migration misses fails visibly instead of silently.
- Verify all saved `.gene` files still load, as was done for the chemistry inversion.

### 1.5 Sites to change

`Operand.Conc` (sealed class), `GeneCodec` serialise + parse + `CONC` regex, `CytoBiologyCore`
`operand`/`operandSnap` + the `conc()` helper + the `selfGateCap` comment, `GeneEditor` kind
labels/index mapping/species builder (`operandKindLabels`, lines ~172, 857, 918-930, 1022, 1316,
1327), `CytoMutation.mutateOperand`, `CytoController` panel label + eval, `CONC_SCALE` in
`CytoTuning`, and the tests in `GeneCodecTest` / `CytoSoaSpecTest` / `ClockProbe`.

---

## 2. Add a difference operand

### 2.1 Shape

```kotlin
data class Diff(val a: Operand, val b: Operand) : Operand()   // evaluates to a - b
```

**Bounded to exactly one level: `a` and `b` must be leaves** (`Constant`/`Chem`/`Biomass`/
`Touching`/`Neighbours`), never another `Diff`. This is a permanent constraint, not a first step.

Three reasons, in order of weight:

1. **The sentence UI.** Progressive disclosure is now the sole gene UI on every width.
   "WHEN RG MINUS GB > 100" still reads as a sentence; nested arithmetic does not.
2. **Mutation space.** `mutateOperand` rolls a flat kind. Recursion lets mutation drift genomes
   into unreadable nested math, with no natural bound — compare `GENOME_MAX_CLAUSES = 4`, which
   exists precisely to bound gate complexity.
3. **Performance** — §3.4.

### 2.2 On OR

Not included. Two genes with one condition each cover the OR case; the divergence (both firing
when both conditions hold) is usually the wanted behaviour, and gene duplication is the
biologically honest encoding. The difference case is different in kind: no amount of gene
duplication constructs a value that isn't expressible.

### 2.3 Self-gate cap

`selfGateCap` computes sub-tick interpolation caps for actions that move their own gate. A
`Diff` clause should **impose no cap**, exactly as `Conc` does today (its value isn't linear in
a single Q when both sides move). This is the correct answer and also the free one — `reads()`
returns false for `Diff` and the clause is skipped.

---

## 3. Performance — the part that matters

### 3.1 Why the gate path is sensitive

`gate()` runs per gene, per cell, per tick, and `clauseHolds` evaluates *two* operands per
clause. Memory records biology as genome-volume bound and the genes sub-phase as hot (it
parallelises 2.54× at 8k cells, which is why it's worth protecting). Anything added here is
multiplied by genome size × population.

### 3.2 Measure first, and measure correctly

- Baseline with `CytoBench` (`-Dcytobench=1`), reading the `bio-sub` breakdown — specifically
  the `genes=` figure, not just total tick time.
- **`benchCyto`, not `benchCytoRender`** — this is a sim-tick change; FPS is not the metric.
- The machine drifts 20–30% and thermally collapses, so **interleave A/B in one process**;
  never compare two separate runs.
- Record in `apps/cyto/PERF.md` alongside the existing entries.

### 3.3 Existing finding: the documented fast path is dead code

`operandFast` (`CytoBiologyCore.kt:747`) is **defined but never called**, and `clauseHoldsFast`
— which its doc comment says calls it — **does not exist anywhere in the tree**. So the
"avoids the when-dispatch" optimisation described in the comment is not in effect; every operand
evaluation goes through the full `when`.

This is worth resolving *before* touching the operand set, because it changes the baseline. Two
honest options: delete the dead function (cleanup, matches the current thread), or actually wire
the fast path and measure whether it was ever worth it. Either way the comment is currently
lying and should not be trusted when reasoning about cost.

### 3.4 The dispatch cost, and the lever that pays for the new operand

`operand()` is a type-switch over a sealed hierarchy — a chain of `instanceof` checks in
practice. Today it is 6-way. The changes push it in both directions: dropping `CONC` takes it to
5, adding `Diff` returns it to 6.

The lever that makes this a non-issue is already established in the codebase:
**`Chem.speciesId` is resolved once at construction and is not a constructor param, so it's
untouched by `equals`/`hashCode`/`copy`** (`CytoGenes.kt:100`). The same trick applies to
dispatch itself:

> Give every `Operand` a precomputed `val kind: Int` at construction, and switch on that int
> instead of on type. An int switch compiles to a `tableswitch` (jump table, one branch)
> rather than a sequential `instanceof` chain. Cost becomes independent of how many operand
> kinds exist — which is what makes adding `Diff` safe.

If that lands first, the operand-set changes are dispatch-neutral by construction, and the plan
stops depending on "we removed one so we can afford to add one."

### 3.5 Specific costs of `Diff`

- **Two leaf evaluations instead of one.** Bounded and known: at most 2 `cachedCount` calls per
  side. `cachedCount` is a **linear scan over ≤32 entries** (`CytoGenes.kt:678`), so a
  `Diff(Chem, Chem)` clause costs up to 4 scans. This is the real cost, and it is why leaves
  must not recurse — recursion makes it unbounded and unpredictable.
- **No allocation.** `Diff` holds two operand references; evaluation returns an `Int`. Nothing
  allocates on the hot path, consistent with the alloc-cut work already recorded in PERF.md.
- **No pointer-chasing beyond one hop.** A flat one-level node is a single dereference; a tree
  would be cache-hostile at population scale.
- **Overflow.** `a - b` on two `Int` counts is safe (counts are bounded by `CELL_CHEM_CAP`), but
  the result is signed — the comparison must not assume non-negative operands. Note `Frac`'s
  ~[-2,2] range is irrelevant here; these are plain `Int`s.

### 3.6 Free win from dropping CONC

`conc()` performs an integer **division** per evaluation (plus a `Long` widen). Division is
multiple-cycle and unpipelined. Removing it takes a division out of the hot gate path for any
genome that used it. Small, but strictly positive and worth noting in the before/after.

---

## 4. Sequencing

Each step lands separately against a green gate. Combining them makes drift unattributable —
if CONC removal and `Diff` addition both re-route the mutation PRNG in one commit, neither
result is interpretable.

1. **Resolve the dead fast path** (§3.3) and record a clean baseline (§3.2).
2. **Int-kind dispatch** (§3.4) — pure refactor, no behaviour change, no PRNG change. Golden
   must be **byte-identical**; if it isn't, something is wrong. Measure.
3. **Drop CONC** — with the migration and version bump (§1.4). `mutateOperand` goes
   `nextInt(6)` → `nextInt(5)`, which **re-routes the mutation PRNG stream and drifts the
   mutation-on golden**. Growth/interact should stay byte-identical. Verify the population
   trajectory before re-baselining, per the golden-gate rule.
4. **Add `Diff`** — `nextInt(5)` → `nextInt(6)`, drifting the mutation-on golden again, same
   verify-then-re-baseline discipline. Measure against step 2's baseline.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Silent genome corruption via the `Chem` fallback | Loud parse error + explicit migration (§1.4) |
| Golden drift conflated across changes | One change per commit, re-baseline separately (§4) |
| Mutation drifting genomes into unreadable math | Hard one-level bound, permanent (§2.1) |
| TPS regression hiding in noise | Interleaved A/B in one process (§3.2) |
| Losing the tunable ratio with no replacement | Accepted deliberately (§1.3) |
