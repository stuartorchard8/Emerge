# Hydrothermal Chemistry: Inverting Cyto's Energy Model (POC Plan)

> ## STATUS: BUILT 2026-07-21 — and §3c below was WRONG. Read this first.
>
> The inversion is implemented. **§3c ("keep `EnergySource.BreakBond` and `ActionType.FormBond` as-is…
> This is additive, not a rip-and-replace") is the one part of this plan that must not be followed**, and
> following it is what broke thermodynamics on the first attempt: keeping either old primitive alongside its
> new mirror means both directions of the same bond pay energy, so a genome can form a bond for +1 and break
> it for +1, returning to its exact starting state having minted 2 quanta from nothing. §5 warned about
> asymmetric *tuning* but missed that mere coexistence is itself the loop.
>
> ### As built
> - `EnergySource.BreakBond` and `ActionType.FormBond` are **gone**. Sources are `Light` and `FormBond`;
>   breaking exists only as the costed `ActionType.BreakBond`.
> - `EnergySource.FormBond` carries the **full reactant pair** (`a`, `b`), not a bond string — it absorbed
>   everything the old FormBond *action* could express, so genomes keep the ability to build arbitrary
>   molecules and morphogenesis still works.
> - **Wildcard operands are gone** (2026-07-21). Synthesis is now the energy source, so a gene has to be
>   readable as "makes X" — and a wildcard reaction has no single product, because what it builds depends on
>   the cytoplasm that tick rather than on the gene. Operands are always exact whole species, and the pair is
>   ordered (`Bond rg b` and `Bond r gb` both build `rgb` but consume different molecules). Legacy `*`
>   markers still parse, dropping the marker and keeping the species. **`ActionType.BreakBond` is still
>   effectively wildcarded** — it splits the richest molecule *containing* the named bond — and de-wildcarding
>   it is the next phase.
> - **No new tuning constant** (§7's `CytoTuning` row is moot). Both directions are pinned at exactly one
>   quantum per bond by construction, and `BreakBond` is excluded from the efficiency-gear multiplier so a
>   single formed bond can never fund `g+1` breaks. The invariant and its proof live on the `EnergySource`
>   kdoc; `ThermodynamicsTest` pins it.
> - Genomes are now **versioned** (`# genome <n>`, `GeneCodec.GENOME_VERSION`). Pre-inversion genomes —
>   saves and loose `.gene` files alike — upgrade on load via `GenomeMigration`, per Stu's three rules
>   (rule 3 conserves the *direction* of each reaction rather than inverting per-slot). Save format v12.
> - Golden gate re-baselined (§6 anticipated this). Trajectory verified first: the world booms to ~4400
>   cells around tick 15k, then declines as ambient monomer is drawn down faster than diffusion recharges
>   it — a carrying-capacity curve, not a runaway. **Whether that decline settles or crashes to zero is an
>   open tuning question**, not a correctness one.
> - Campaign genomes are still authored as pre-inversion text and migrate on load. They are coherent but
>   behave differently (e.g. the Ch8 swimmer's `Light : FormBond r g` feed gene became `Light : Break rg`),
>   and the coach copy still says "BOND". **Re-authoring the campaign is outstanding.**


## 1. The idea

Today, energy flows **light → bond**. Autotrophs spend light quanta to fuel
`FormBond`; the resulting bond is a *store* of that energy, later released by
`BreakBond`-sourced genes (heterotrophy — digest a neighbour, power your own
Repair/Convert/Divide). Monomers are inert raw material; light is the only
free lunch.

The proposal: flip which side of the bond is "spent" and which is "free".
**Monomers become the high-energy state.** Forming a bond is exothermic — it
releases a quantum, like a hydrothermal-vent redox reaction (H₂S + O₂-type
chemistry, not photosynthesis). Breaking a bond becomes endothermic — it
*costs* energy, the way building currently does. Light either goes away
entirely for the POC, or becomes a minor secondary tap later.

Biomass stays Σ(count × bondCount) exactly as it is now (`CytoBiologyCore.kt`
`totalBiomassBonds`, referenced at lines 217/252/262/369/708/842) — you asked
to keep that, and it still works: "more structure = more biomass" is
orthogonal to which direction energy flows.

**Good news:** the world already seeds as pure monomers. `CytoMatterField.seededUniform()`
(`CytoMatterField.kt:97-100`) fills only the monomer columns (`MONO = [A,B,C]`,
`:75`) at a uniform level — that's already your "world starts all monomer,
maximally energetic" hydrothermal-vent starting condition. **No change needed
there for the POC.** All the work is in the energy wiring, not the world seed.

## 2. Current mechanism (the thing being inverted)

`CytoGenes.kt:21-29` — a gene has exactly one `EnergySource`:
- `Light` — free environmental flux (`CytoBiologyCore.kt:362`, `quantaShare`
  computed from the light field, formula in `CytoTuning.kt:113-116`,
  `LIGHT_QUANTA_SCALE = 120 * CHEMISTRY_SCALE`).
- `BreakBond(bond)` — break one instance of `bond` in the richest cytoplasm
  molecule holding it (`richestWithBond`, `CytoBiologyCore.kt:466`), split
  into two fragments, release one quantum per bond broken
  (`CytoBiologyCore.kt:362`: `energyUnits = snap.count(breakSpId) / n`).

`ActionType.FormBond` (`CytoBiologyCore.kt:339-348`) is **only ever an
action**, never an energy source: it consumes two reactant molecules
(`endAId`, `startBId`) and mints the joined product, funded by whichever
`EnergySource` the gene declares (usually `Light`). There is no
`ActionType.BreakBond` — active, costed bond-breaking as a *goal* doesn't
exist today; the only things that break bonds are (a) `EnergySource.BreakBond`
as fuel-harvesting (a side effect of paying for something else), and (b)
passive, energy-free background processes: environmental `decayAll`
(`CytoMatterField.kt:556`, "free molecules break their leftmost bond") and
per-cell size-proportional `degrade` (`CytoBiologyCore.kt:701-707`, wear-based).

So today: **build costs energy, break releases it, and background entropy
also breaks bonds for free.** The inversion needs to touch exactly the first
two; the third (background decay) needs a deliberate decision, not a silent
carry-over (see §5).

## 3. The core change: two new primitives

Add the mirror image of what exists today.

### 3a. `EnergySource.FormBond(bond)`

In `CytoGenes.kt`, alongside `EnergySource.BreakBond`:

```kotlin
/** Form one instance of [bond] (e.g. "rg") from its two free monomer/fragment
 *  reactants, releasing a quantum per bond formed — the hydrothermal energy
 *  source. Mirrors BreakBond exactly, with join instead of split. */
data class FormBond(val bond: String) : EnergySource()
```

Wire it into `CytoBiologyCore.kt` next to the existing `src is
EnergySource.BreakBond` branch (~line 311-316). You need a `richestFormable`
analogue to `richestWithBond`: instead of finding the richest molecule that
*contains* the bond (to split), find the two reactant species whose join
produces it — for the POC this can be as narrow as "the two monomers
`bond[0]` and `bond[1]`" (mirrors the existing narrow, exact-match `FormBond`
action reactant resolution at `CytoBiologyCore.kt:330-331`, not the wildcard
path). Energy units available = `min(count(monomerA), count(monomerB)) / n`,
same shape as `energyUnits = snap.count(breakSpId) / n` at line 362. On
spend, consume the two monomers and credit the joined product into
cytoplasm — this *is* the harvesting side effect, exactly like `BreakBond`
credits fragments today (lines 407-409), just joining instead of splitting.

### 3b. `ActionType.BreakBond`

In `CytoGenes.kt`'s `ActionType` enum, add `BreakBond`. In
`CytoBiologyCore.kt`'s action-resolution `when` (~line 320-337), add a branch
mirroring `FormBond`'s but inverted: pick the richest molecule holding the
target bond (reuse `richestWithBond`), and the action *costs* `k` quanta to
split `k` instances of it into fragments credited to cytoplasm. This is
where "digest a neighbour's biomass" or "actively break down your own
storage into transportable monomers" now lives as a deliberate, funded
choice — not a free side effect of fuelling something else.

Also add it to the `eff`/`capGear` `when` branches (lines 341, 348) exactly
like `Convert`/`Repair` — it should get the efficiency-gear treatment (rate
↔ efficiency), and to the final apply `when` (~line 410 onward) alongside
`Convert`/`FormBond`.

### 3c. What happens to the old pair?

Keep `EnergySource.Light` (for a later secondary-energy experiment) but stop
using it in the POC founder genomes. Keep `EnergySource.BreakBond` and
`ActionType.FormBond` as-is — they're still valid gene shapes, just no
longer what founders lean on for primary metabolism. This is additive, not a
rip-and-replace: you're giving genes a new option, then rewriting the
starter genomes to use the new option as primary. That keeps the blast
radius small and the old golden-gate genomes (if unchanged) as a regression
control (§6).

## 4. Rewriting the founder genome(s)

Currently (`CytoGenes.kt:677-679`, the autotroph/`Collector` genome):
```
BreakBond("rg") + Biomass>DIVIDE_BIOMASS → Divide
Light + Biomass<GROW_BIOMASS           → Convert("rg")
Light + Chem("rg")<GROW_BIOMASS        → FormBond("r","g")
```
Light funds growth; a hoarded `rg` reserve funds division.

Hydrothermal founder — replace `Light` with `FormBond("rg")` throughout:
```
FormBond("rg") + Biomass>DIVIDE_BIOMASS → Divide
FormBond("rg") + Biomass<GROW_BIOMASS   → Convert("rg")
FormBond("rg") + Chem("rg")<GROW_BIOMASS → FormBond("r","g")
```
Note the third gene both *sources from* and *acts on* `rg` bond-formation —
that's intentional and matches the existing symmetry where `BreakBond("rg")`
already sources from and acts on the same bond in Repair genes today
(`CytoGenes.kt:689`). The cell builds `rg` bonds as its metabolism; each
build both grows its `rg` reserve *and* pays for that growth. Division now
becomes viable off `FormBond` (unlike the current `Divide` comment at
`CytoBiologyCore.kt:383-386` explaining why light-division is emergently
non-viable) — cross-check the size-scaling cost (`biomass/4`,
`CytoBiologyCore.kt:391`) against your new per-tick `FormBond` yield the same
way that comment reasons about `Light`, and retune the equivalent of
`LIGHT_QUANTA_SCALE` for `FormBond` if division fires immediately/never.

Do the same swap for `HETEROTROPH_GENES` (`CytoGenes.kt` ~714-734) if you
want a second POC organism — or, more interestingly, make the heterotroph's
primary energy source `ActionType.BreakBond`-adjacent (i.e. give it
`EnergySource.FormBond` too but gate its actions on *consuming another cell's
biomass* rather than free-field monomers) — that's a stretch goal, not POC
scope.

## 5. The thermodynamic-consistency trap — read this before tuning numbers

The current model can't run a perpetual-motion loop because build costs
energy and break yields it — you can't break-then-reform the same bond for
net-positive energy, the accounting is symmetric by construction (whatever
you got from breaking, you spend rebuilding, net zero minus overhead).

**The inverted model has the same shape and the same safety, IF you keep it
symmetric** — `FormBond` credits a quantum, `BreakBond` (as an action) costs
one, using the same `LIGHT_QUANTA_SCALE`-equivalent constant on both sides.
Where it can go wrong: if you tune `FormBond`'s yield and `BreakBond`'s cost
independently (e.g. different constants, or one uses `gP1` efficiency-gear
scaling and the other doesn't), a cell could farm net-positive energy by
cycling `monomer → bond → monomer` — free energy from nothing, since the
world's total bond count would oscillate but the organism's energy ledger
would ratchet up. **Keep FormBond-yield-per-bond and BreakBond-cost-per-bond
numerically identical**, at least for the POC, and only let them diverge
later as a deliberate "catalysis" mechanic once the base loop is proven closed.

**Background decay is the other hazard.** `CytoMatterField.decayAll`
(`CytoMatterField.kt:556`, driven by `CytoTuning.kt:79-80`'s environmental
decay rate) and per-cell `degrade` (`CytoBiologyCore.kt:701-707`) currently
break bonds *for free*, no energy accounted either way — today that's fine,
it's just entropy eroding structure back to raw material. In the inverted
model, environmental decay breaking a bond back into monomers is actually
**regenerating the world's fuel supply for free** (geological reformation —
consistent with the hydrothermal-vent framing: geology keeps recharging the
vent, biology can't). That's arguably a *feature*, not a bug — it's what
prevents the world running out of monomers once life starts consuming them.
But per-cell `degrade` breaking an organism's *own* stored bonds for free is
now handing that organism free energy internally (its wear-driven bond loss
credits nothing today, but under the new frame "a bond broke" ⇒ conceptually
energy *should* have been released). Decide explicitly: either (a) leave
`degrade` as pure biomass loss with no energy credit — simplest, treat it as
"this bond's energy was already spent/dissipated as heat, not recovered" —
or (b) route degrade's broken bonds through the same fragment-crediting path
as `BreakBond`-the-action, giving cells a trickle of free energy proportional
to wear. Start with (a) for the POC; it's strictly simpler and doesn't need
new plumbing. Note the decision in a code comment wherever `degrade` is
defined, since a future reader will ask the same question.

## 6. Validation plan

- The golden-gate trajectory (`CytoGoldenTest`, [[reference_cyto_golden_gate]]
  in memory) will **not** hold — you're changing core metabolism, not doing a
  neutral refactor. Don't try to preserve it; re-baseline once the new
  founder genomes are stable and you like what you see, the same way the
  2026-07-15 diffusion work did.
- Before re-baselining, eyeball the population/biomass curve over a few
  thousand ticks the way past sim-changes have (per
  [[reference_cyto_golden_gate]]): does the world still support life at all
  (monomers deplete to zero and starve everything), find an equilibrium, or
  blow up (free-energy loop from §5)?
- Use the existing headless agent harness
  (`./gradlew :apps:cyto:desktop:cytoAgent`, [[reference_cyto_agent_harness]])
  to screenshot early ticks and confirm the world visually reads as "soup of
  monomers, pockets of life building structure" rather than instant
  full-field polymerisation or instant collapse.
- Suggested manual experiment order: (1) implement `FormBond`-as-energy-source
  and `BreakBond`-as-action with symmetric constants, (2) swap just the
  autotroph founder over, run headless, watch population curve, (3) tune the
  new constant until behaviour is stable (neither instant death nor
  runaway), (4) only then decide whether heterotrophs need their own
  rewrite or can stay `BreakBond`-sourced (still valid, just now "eating"
  costs the *prey* structure without the same free-lunch framing the
  autotroph gets from ambient monomers).

## 7. Summary of file touches

| File | Change |
|---|---|
| `CytoGenes.kt` | Add `EnergySource.FormBond(bond)`; add `ActionType.BreakBond`; rewrite `AUTOTROPH_GENES` (and optionally `HETEROTROPH_GENES`) to source from `FormBond` |
| `CytoBiologyCore.kt` | Mirror the `BreakBond`-as-source branch (~311-316, 362, 407-409) for `FormBond`-as-source; mirror the `FormBond`-as-action branch (~339-348) for `BreakBond`-as-action; add `BreakBond` to the `eff`/`capGear`/apply `when` blocks (341, 348, ~410+); add a `richestFormable`-equivalent lookup near `richestWithBond` (466) |
| `CytoTuning.kt` | New constant mirroring `LIGHT_QUANTA_SCALE` (113-116) for FormBond yield-per-bond; must equal the BreakBond-action cost-per-bond (§5) |
| `CytoMatterField.kt` | No change required — `seededUniform` (97-100) already gives you the monomer-soup start |
| `degrade` (`CytoBiologyCore.kt:701-707`) | No code change for POC, but add a comment recording the (a)-vs-(b) decision from §5 |
| Golden gate | Re-baseline after the change settles, don't try to preserve the old trajectory |
