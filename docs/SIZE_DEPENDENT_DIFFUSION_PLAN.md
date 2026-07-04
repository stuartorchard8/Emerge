# Size-Dependent Diffusion — Implementation Plan

## Goal

Scale the diffusion rate of chemicals based on their molecular size (atom count).
- **Monomers** (1 atom): diffuse at full speed (unchanged)
- **Larger molecules** (polymers): diffuse more slowly, proportionally to size

**Why**: Cells should be able to store resources in longer molecules without passively losing them to the environment. Import genes become a *choice* for concentration above ambient, not a *necessity* for homeostasis. Loosely inspired by real-world membrane permeability (larger molecules cross less readily).

---

## Current State

### Diffusion happens in two places:

**1. Cell ↔ Environment** (`CytoMatterField.balance()`, line 190)
- A cell opens its circular footprint on the quad-tree matter field
- For each `canDiffuse` species, balances cytoplasm count toward `cEff / n` across all leaves in the footprint
- **Current behavior**: exact integer equalization — all species move at the same rate toward equilibrium
- Monomer `"a"` and polymer `"abcde"` diffuse out at identical speeds

**2. Cell ↔ Cell** (`CytoBiologyCore.diffuse()`, line 410)
- Each cell sends `⌊count / CYTOPLASM_DIFFUSE_DENOM⌋` (currently 11) of each diffusible species to each welded neighbor
- Snapshot-based, order-independent, conservative
- **Current behavior**: single fixed divisor for all species

### What's already available

- `SpeciesRegistry.atomCount(id)` — returns molecule length (1 for monomers, 2+ for polymers)
- `Handleable.canDiffuse(id)` — gates *which* species are diffusible at all (metabolic reach)
- All diffusion is already integer-based and matter-conserving

---

## Design Decisions

### Mathematical model

**Linear penalty on the diffusion divisor:**

```
effectiveDenom = baseDenom * (1 + (atomCount - 1) * scaleFactor)
```

- Monomers (atomCount = 1): `effectiveDenom = baseDenom` — **unchanged**
- A 5-atom molecule with `scaleFactor = 1.0`: `effectiveDenom = baseDenom * 5` — 5× slower
- Tunable via `DIFFUSION_SCALE_FACTOR` in CytoTuning

This is the simplest model that achieves the goal and is easy to tune.

### Conservation

Integer truncation (`⌊count / denom⌋`) is already used throughout — no change in conservation semantics. Less matter moves for larger molecules, but nothing is created or destroyed.

### Backward compatibility

`scaleFactor = 0` restores exactly the current behavior. Existing saved states and golden tests baseline with 0.

---

## Changes

### 1. `CytoTuning.kt` — add parameter

```kotlin
/** Size-dependence of diffusion rate. Each molecule beyond a monomer scales its diffusion divisor
 *  by this factor: `denom = base * (1 + (atomCount - 1) * scale)`. Monomers diffuse at full speed;
 *  polymers slow down. `0` = current behavior (no size effect). ⚙ */
const val DIFFUSION_SCALE_FACTOR = 0
```

### 2. `CytoMatterField.kt` — cell↔environment diffusion

**File**: `demos/cyto/src/commonMain/kotlin/org/emerge/demo/cyto/sim/CytoMatterField.kt`

**Method to modify**: `balance(sp: Int, cEff: Int)` (line 190)

**Current logic**:
```kotlin
fun balance(sp: Int, cEff: Int): Int {
    val n = fpLeaves.size
    val bucket = cEff / n
    var returned = 0
    for (leaf in fpLeaves) {
        val store = leaf.store!!
        val e = store.count(sp); val total = e + bucket
        val half = total / 2
        val eNew: Int; val bNew: Int
        if (e >= bucket) { eNew = total - half; bNew = half }
        else { eNew = half; bNew = total - half }
        val d = eNew - e
        if (d != 0) store.add(sp, d)
        returned += bNew
    }
    return returned - bucket * n
}
```

**New logic** — add a `scaleFactor` parameter. Instead of exact equalization, scale the *amount moved* by diffusion rate:

```kotlin
fun balance(sp: Int, cEff: Int, scaleFactor: Float): Int {
    val n = fpLeaves.size
    val atomCount = SpeciesRegistry.atomCount(sp)
    val denom = 1f + (atomCount - 1) * scaleFactor   // 1.0 for monomers, higher for polymers

    val bucket = cEff / n
    var returned = 0
    for (leaf in fpLeaves) {
        val store = leaf.store!!
        val e = store.count(sp)
        val total = e + bucket

        // Current: half = total / 2 (full equalization)
        // New: damp toward equilibrium by scaleFactor
        // Move only 1/denom of the distance to equal target
        val target = if (e > bucket) e - (e - bucket) / denom
                     else if (e < bucket) e + (bucket - e) / denom
                     else e
        // ... truncate to int, move the difference
    }
}
```

**The key change**: instead of moving `half` (full equalization), move `fullAmount / denom`. A 5-atom molecule with `scaleFactor=1.0` only moves 1/5 of the way toward equilibrium per tick.

### 3. `CytoBiologyCore.kt` — cell↔cell diffusion

**File**: `demos/cyto/src/commonMain/kotlin/org/emerge/demo/cyto/sim/CytoBiologyCore.kt`

**Method to modify**: `diffuse()` (line 410)

**Current logic** (line 424):
```kotlin
val out = w.cytoplasm.countAt(i) / CYTOPLASM_DIFFUSE_DENOM
```

**Change**:
```kotlin
val atomCount = SpeciesRegistry.atomCount(species)
val denom = CYTOPLASM_DIFFUSE_DENOM * (1 + (atomCount - 1) * CytoTuning.DIFFUSION_SCALE_FACTOR)
val out = w.cytoplasm.countAt(i) / denom
```

Where `denom` is `Int` (cast from the Float result). This changes the send amount per neighbor.

### 4. Wire `balance()` through `passiveEnvExchange()`

**File**: `CytoBiologyCore.kt`, method `passiveEnvExchange()` (line 49)

**Current call** (line 63):
```kotlin
val delta = grid.balance(sp, cEff)
```

**Change**:
```kotlin
val delta = grid.balance(sp, cEff, CytoTuning.DIFFUSION_SCALE_FACTOR)
```

### 5. Tests / golden re-baselining

- Run `CytoGoldenTest` after setting `DIFFUSION_SCALE_FACTOR = 0` — should pass (no regression)
- Set `DIFFUSION_SCALE_FACTOR = 1.0` (or desired value), run simulation, re-baseline golden output
- Consider a small focused test: spawn a cell with monomers + a polymer, verify polymer persists in cytoplasm longer while monomers equilibrate

---

## File Summary

| File | Lines affected | Change type |
|------|---------------|-------------|
| `CytoTuning.kt` | +5 (new const) | Add parameter |
| `CytoMatterField.kt` | `balance()` ~20 lines | Dampen equalization by size factor |
| `CytoBiologyCore.kt` | `diffuse()` ~3 lines, `passiveEnvExchange()` ~1 line | Pass size factor |
| Tests | re-baseline | No structural changes |

**Total**: ~30 lines changed across 3 source files + golden re-baselining.

---

## Tuning Guidance

Start with `DIFFUSION_SCALE_FACTOR = 0.5`:
- Monomers: unchanged
- Dimers (2 atoms): denom × 1.5
- Trimers (3 atoms): denom × 2.0
- 5-mers: denom × 3.0
- 10-mers: denom × 5.0

This gives polymers meaningful retention while keeping monomer diffusion responsive. Increase toward `1.0` for stronger effect, or lower for gentler scaling.

---

## Out of scope (for now)

- Environmental quad-tree decay (`MATTER_DECAY_PERIOD` in CytoTuning) — already scales with complexity
- Cell↔cell diffusion — included in plan but deprioritized if environment retention alone solves the problem
- Genetic mutations that adapt diffusion rates — not part of the genome model
- Non-linear (exponential/sqrt) scaling models — linear is sufficient for the balance goal
