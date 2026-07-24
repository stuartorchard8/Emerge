# Cyto — A Player's Tutorial

*How to read a cell, write a genome, and grow a body.*

> **⚠️ STALE — PRE-INVERSION (flagged 2026-07-23, not yet re-authored).** This document was written before
> the chemistry inversion of 2026-07-21, which reversed the thermodynamics: **forming a bond RELEASES
> energy; breaking one COSTS it.** §5 "Energy sources" therefore teaches the energy model backwards, and
> **16 code examples use `Break <x>` in the energy-source position**, where a current genome uses `Bond` (or
> `Light`). The *structure* of everything here — gene grammar, conditions, the actions, morphogens, the
> worked genomes — is still accurate; it is specifically the energy direction and those examples that lie.
> Re-authoring is a job of its own and wants a human voice; until then read source-position `Break` as
> `Bond`. See `HYDROTHERMAL_CHEMISTRY_PLAN.md` and memory `project_cyto_chemistry_inversion`.

Cyto is a god-sim of single-genome development. **One cell carries one genome. It divides into
copies of itself, and those copies — running the *same* genome but in different local conditions —
grow, differentiate, and arrange themselves into a body.** Nothing about the body is drawn or
scripted; it *develops*, the way a real embryo does, out of thousands of cells each following the
same rules.

That is also why Cyto looks impenetrable at first: what you're watching is the *output* of a
program, and the program is written in chemistry. This tutorial teaches you to read and write that
program. By the end you'll be able to build a genome that grows, one that keeps time (a biological
clock), and one that lays down a morphogen gradient so cells can tell *where they are* in the body.

The design lineage is SimulifeHub's morphogenesis videos (see `SimulifeHub-Part1.md`), but Cyto's
genetics are **not** the same. SimulifeHub has dedicated genes for "produce morphogen", "start
timer", "apoptosis". Cyto has none of those. It has **six primitive actions and a chemistry**, and
timers, morphogens, and differentiation are all things you *build* out of them. That's harder to
start with and far more open-ended once you get it.

---

## Table of contents

1. [The 60-second mental model](#1-the-60-second-mental-model)
2. [How to drive the game](#2-how-to-drive-the-game)
3. [Atoms, molecules, and bonds](#3-atoms-molecules-and-bonds)
4. [Anatomy of a gene](#4-anatomy-of-a-gene)
5. [Energy sources](#5-energy-sources)
6. [Conditions: how a gene decides to fire](#6-conditions-how-a-gene-decides-to-fire)
7. [The bloat tax: why lean genomes win](#7-the-bloat-tax-why-lean-genomes-win)
8. [The seven actions, one at a time](#8-the-seven-actions-one-at-a-time)
9. [Diffusion and the three roles a molecule can play](#9-diffusion-and-the-three-roles-a-molecule-can-play)
10. [Walkthrough: the autotroph, line by line](#10-walkthrough-the-autotroph-line-by-line)
11. [Biological clocks](#11-biological-clocks)
12. [Morphogen gradients](#12-morphogen-gradients)
13. [Experiments to try](#13-experiments-to-try)
14. [Troubleshooting](#14-troubleshooting)
15. [Grammar cheat-sheet](#15-grammar-cheat-sheet)

---

## 1. The 60-second mental model

- **Matter is finite; energy is free.** Atoms are conserved — they cycle environment → cell →
  biomass → back to the environment on death. You can never build from atoms that don't exist. Light,
  by contrast, is an unlimited flux; it's *power*, not *material*.
- **A cell holds two chemical pools.** *Cytoplasm* is mobile: genes act on it and it diffuses to
  neighbours. *Biomass* is locked structure: it sets the cell's size and it's what keeps the cell
  alive. Growth is moving matter from cytoplasm into biomass.
- **A genome is a flat list of genes.** Every gene is dead simple — *one* energy source, *one*
  condition, *one* action. There are no weighted sums, no neural nets. Complexity comes from *many
  simple genes interacting through the shared chemistry*, not from any single clever gene.
- **The same genome runs in every cell.** Two cells behave differently only because their local
  chemistry differs — different light, different neighbours, a different morphogen dose inherited at
  division. All of development is *engineering those differences*.

---

## 2. How to drive the game

**Painting cells.** The bottom-left palette is your **genome library**. Each swatch is a named
genome loaded from a `.gene` file in the `cyto-genomes/` folder next to the game. Click a swatch to
select it, then click in the world to drop a cell running that genome. Two defaults — `Autotroph`
and `Heterotroph` — are seeded on first run.

**Inspecting a cell.** Click a cell to select it. An info panel opens (top-right) showing its type,
size, chemistry, and its **full gene list**. This is the single most useful habit in the game: when
something surprises you, *click the cell and read its genes and its cytoplasm counts*. Almost every
"why isn't this working" question is answered there.

**Editing a genome live.** In the info panel, tap a gene to open the editor. Every field —
energy source, each condition operand, comparator, action — is a click-to-expand dropdown; the
threshold is a hold-to-repeat `±` stepper. Edits accumulate in a draft and only apply on **DONE**
(**CANCEL** discards). **DUPLICATE** and **DELETE** act on the live genome immediately. Because
the genome is per-cell, you're editing *that cell's* copy — its descendants inherit the change, the
rest of the world doesn't.

**Saving a genome to the library.** The editor's **EXPORT** button opens the Save-Genome flow;
name it and it's written to `cyto-genomes/<name>.gene` and appears as a new palette swatch. A
same-name save overwrites.

**Authoring genomes as text.** You don't have to build everything by clicking. A `.gene` file is
plain text (the format in this tutorial) with an optional colour header:

```
# genome: MyCreature
# color: 44cc55ff
Light : rg < 3000 : FormBond r g
Light : Biomass < 3000 : Convert rg
Break rg : Biomass > 2000 : Divide
```

Drop that in `cyto-genomes/`, restart (or re-open the menu), and it's a swatch you can paint.

**Other controls.** Scroll to zoom, right-click and drag to pan, drag a cell to move it. Left-click
is world interaction, right-click is the camera: a right-click that didn't pan deselects. **Space**
pauses, **`[` / `]`** slow down / speed up the sim, **Esc** deselects (or opens the menu). The
**Mut** button cycles the mutation rate (off → 1/1M → … → 1/1k) — keep it **off** while you're
hand-authoring, or your careful genome will drift. **Light** and **Matter** buttons overlay the
environment fields; **Color** cycles how cells are tinted (by biomass mix vs cytoplasm mix).

---

## 3. Atoms, molecules, and bonds

The whole chemistry is built from **three atoms: `r`, `g`, `b`** (they render as red / green / blue,
which is why a cell's colour tells you its chemical makeup).

- A **molecule** is a string of atoms: `r`, `rg`, `rgb`, `grb`, …
- A **bond** is an adjacent pair. `rgb` contains bonds `rg` and `gb`. A molecule of length *L* has
  *L−1* bonds.
- **No repeated bonds.** A molecule may contain each ordered bond-type at most once. So `rgr` is
  legal (bonds `rg`, `gr`) but `rgrg` is not (two `rg`). This one rule bounds the entire universe of
  molecules to a finite set — there's no runaway polymerisation, and no decay hack needed to keep it
  finite.
- **Bonds are batteries.** Forming a bond *costs* exactly one energy quantum; breaking it *releases*
  one. So `r + g ⇌ rg` is energy-neutral — there's no free-energy exploit anywhere.
- **Biomass = bond count.** A cell's size and its life depend on the total number of bonds locked in
  its biomass. Long molecules are worth more per atom (more bonds), but the no-repeat rule caps how
  long they get.

Everything a genome does is: gather atoms, join them into molecules (storing energy in bonds), lock
molecules into biomass (grow), and break molecules back apart (to release energy for expensive
actions like division).

---

## 4. Anatomy of a gene

Every gene is one line, three colon-separated parts, plus an optional efficiency gear:

```
<energy source> : <condition> : <action> [@<gear>]
```

For example:

```
Break rg : Biomass > 2000 : Divide @4
```

Read it as: *"Powered by breaking an `rg` bond, whenever my biomass exceeds 2000, divide — at
efficiency gear 4."*

Each tick, for each cell, Cyto scans the genome: every gene whose **condition** holds and whose
**energy source** can supply at least one quantum is *active*. Active genes share out the cell's
energy (see the bloat tax below) and perform their **action** that many times.

There is no ordering, no priority, no "if/else". Genes are a flat set that all run in parallel
through the shared chemistry. Logic is expressed *chemically*: one gene's product is another gene's
condition.

---

## 5. Energy sources

Every action costs energy, measured in **quanta** (one quantum = one bond's worth = one "op" of the
action). A gene draws energy from exactly one source, this tick, use-it-or-lose-it — genes can't
bank, pool, or borrow.

**`Light`** — autotrophy. Free environmental flux at the cell's position, scaled by how *exposed*
the cell's surface is. A buried interior cell is shaded and gets little; a surface cell gets the
full amount. Cells sharing a spot split the light by size, so a bigger neighbour literally
overshadows and starves a smaller one — growth is a weapon, not just self-benefit.

**`Break <bond>`** — heterotrophy / catabolism. Breaks one instance of the named bond in a
cytoplasm molecule per op: it releases that bond's stored quantum to power the action *and* splits
the molecule into two fragments returned to cytoplasm. `Break rg` means "tear an `rg` bond out of
something in my cytoplasm to power this."

This is the crucial distinction: **`Light` is free but weak and only where it's bright; `Break` is
strong but spends your own stored matter.** Cheap, continuous work (making a molecule, importing)
runs off Light. Expensive, bursty work (dividing) is usually paid for by Breaking a reserve you
built up earlier.

---

## 6. Conditions: how a gene decides to fire

A condition is one or more **clauses** joined by `&` (AND). The gene fires only when *all* clauses
hold. Each clause is `<operand> <comparator> <operand>`, where the comparator is `<` or `>`.

The operands (either side of the comparator):

| Operand | Meaning |
|---|---|
| an integer, e.g. `2000` | a fixed constant |
| a species, e.g. `rg` | the **count** of that molecule in cytoplasm |
| `Biomass` | total biomass (bond count) — also drives size and the death threshold |
| `Touching` | number of un-welded cells this cell is bumping this tick |
| `Conc(rg)` | the **concentration** of `rg` — its count normalised by body size |

Because *both* sides are operands, you can compare two live quantities, not just a quantity against a
number:

```
Break gg : gr < gg : ...      # fires while my 'gr' count is below my 'gg' count
```

**How to express the usual logic:**

- **NOT / absence** → use `<`. `rg < 1` means "I have no `rg`". `Conc(m) < 200` means "morphogen
  is low here".
- **OR** → write two genes with the same action. There's deliberately no `|`.
- **A band ("I'm in the middle")** → two clauses. `200 < Conc(m) & Conc(m) < 600` fires only in a
  concentration window — this is the primitive behind reading position in a gradient.

There are deliberately **no weighted sums**. Monotone "add up the inputs" activation saturates and
doesn't evolve well; binary clauses that gate other genes do.

**`Chem` vs `Conc`** matters a lot. `Chem` (a bare species token) is the raw count — a big cell
trips `rg > 500` just by being big. `Conc` divides by size, so it reads the same regardless of how
big the cell is. Use `Conc` for anything positional or timed (morphogens, clocks); use raw counts and
`Biomass` for absolute quantities (reserves, the divide threshold).

---

## 7. The bloat tax: why lean genomes win

Each tick a cell has a fixed energy budget. **Every active gene gets only a `1/N` slice of the
relevant source, where N is the number of active genes.** Ten genes firing at once means each does a
tenth of the work; the unclaimed slices are simply lost.

The consequence is design pressure toward **lean genomes**: a creature that does the same job with
three genes out-grows one that does it with ten. When you author, gate genes tightly so only the
ones that need to run *are* running — a gene that's always active is taxing every other gene even
when it has nothing useful to do.

---

## 8. The seven actions, one at a time

Below, each action with the minimal genome that demonstrates it. Paint a single cell somewhere
bright and watch.

### Convert — grow

`Convert <species>` locks one molecule of that species from cytoplasm into biomass. Biomass is size
and life; it also slowly *degrades* (loses a bond per period, fragments back to cytoplasm), so a cell
must keep converting just to hold its size. This is the growth action.

```
Light : Biomass < 3000 : Convert rg      # while small, lock 'rg' into structure
```

On its own this does nothing — the cell has no `rg` to convert. You need a source of `rg` first,
which is the next action.

### FormBond — build molecules

`FormBond <a> <b>` joins a molecule *ending* in atom `a` to one *starting* with atom `b`. By default
the operands are **exact species**: `FormBond r g` joins monomer `r` to monomer `g` → `rg`.
`FormBond rg b` → `rgb`. Forming the bond costs one quantum.

```
Light : rg < 3000 : FormBond r g         # under light, make 'rg' until I have 3000
```

The refusal rule bites here: `FormBond` is silently a no-op if the product would repeat a bond
(`FormBond rg rb` would try `rgrb`? no — it joins the whole molecules `rg`+`rb` → `rgrb`, which is
legal; but `FormBond ab ab`→`abab` repeats `ab` and is refused). If a `FormBond` gene "does nothing",
check whether its product is legal and whether the reactants actually exist in the cytoplasm.

**Wildcards (advanced).** A `*` makes an operand a pattern instead of an exact species: `*r` = "any
molecule ending in `r`", `r*` = "any starting with `r`" (it picks the most abundant match). Exact is
usually what you want; wildcards are for robustness when the exact species drifts under mutation.

Put Convert + FormBond together and you have a cell that grows — see the [autotroph
walkthrough](#10-walkthrough-the-autotroph-line-by-line).

### Import / Export — one-way channels

By default, cells exchange cytoplasm with their surroundings **passively and freely, down-gradient**:
each tick a cell absorbs roughly half the difference when the environment is richer than it, and
leaks when it's richer than the environment. This free flux is what makes the food web flow —
an autotroph's surplus bleeds out to feed heterotrophs.

`Import <species>` and `Export <species>` are **active, one-way gates** that fight the gradient:

- `Import rg` biases the cell to pull `rg` *in* above ambient **and clamps it to inward-only** — the
  species can never leak back out. Use it to concentrate a scarce resource, or to hold a reserve
  against passive leak.
- `Export rg` is the opposite: pushes `rg` *out* below ambient and clamps it to outward-only — a
  secretion / waste-dump valve.

Both cost energy per op (that's the price of pumping against a gradient).

```
Light : rg < 500 : Import rg              # actively hoard 'rg' even when the neighbourhood is poor
```

### Divide — divide

`Divide` splits the cell in two. Biomass and cytoplasm halve (that halving is the natural brake —
both daughters drop back below any biomass gate, so back-to-back division needs real surplus).
Division is a *bulk* cost (~a quarter of your biomass in energy), which a per-tick light trickle
can't fund — so it's almost always powered by chemistry, cashing in a reserve you stored:

> **Division is the one all-or-nothing action, and it does not scale with you.** The cost is
> `biomass/4` and the whole bill falls due in a **single tick** — energy cannot be banked toward it.
> But the cytoplasm you pay from does *not* grow with your body: it settles at whatever the membrane
> can draw in. So a cell that keeps growing eventually passes the point where no reaction it can run
> in one tick covers its own division, and it can never split again — whatever powers it.
>
> **A grower that wants to reproduce needs a growth cap**, e.g. `Biomass < 3000` on its `Convert`
> gene. This is not a nicety; it is the difference between a lineage that colonises and one that
> becomes a single large cell and stops. (The Divide chapter of the campaign teaches exactly this,
> and the gene card now shows it: a DIVIDE gene that cannot fund itself draws its **fuel molecule**
> in blocking orange, even though its gate is passing.)

```
Break rg : Biomass > 2000 : Divide       # once big enough, break 'rg' to pay for splitting
```

Divide has the richest set of options, and they are the whole basis of building a *shaped*,
*differentiated* body:

- **`Divide <morphogen>`** — *asymmetric division*. The named species is handed **whole to one
  daughter** while everything else halves normally. The two daughters now have different chemistry
  from a single genome — this is the seed of all differentiation (see §12).
- **`... mother`** — keep the morphogen in the **mother** instead of the daughter. Because the
  daughter is spawned *outward* (toward free space) and the mother steps *inward*, this choice picks
  the body plan: daughter-retention rides the signal to the growing **edge** (a head–tail axis);
  mother-retention keeps it **centred** (a skin-core radial plan).
- **`... sever`** — the daughter rejects all welds to the mother and splits off as a separate
  single-celled organism. This is how a colony *reproduces* (buds off a founder) rather than just
  growing bigger. The default `Autotroph` uses this.
- **`... along <axis>` / `... across <axis>`** — *oriented division*. Place the daughter along the
  gradient of an axis-morphogen (extend a **thread**) or across it (widen into a **sheet**). This is
  the anisotropy lever that makes limbs and layers instead of blobs.

These four parameters are independent — you can combine them, e.g. `Divide m mother across axis`.

### Repair — hold the body together, and adhere

A body is held together by **welds** (springs) between cells, and welds accumulate stress damage.
There is **no free healing** — `Repair` spends energy to heal the most-damaged connection per op. A
multicellular body that doesn't budget for Repair will fray apart under its own physics.

```
Break rg : rg > 0 : Repair                # burn stored 'rg' to keep my welds intact
```

Repair also **welds touching cells**: a firing Repair gene adheres to un-welded cells it's bumping,
which is how a body knits itself together as it grows.

### Contract — move

`Contract` pulls the cell's radius in below its biomass baseline (each op nudges it in a step; it
elastically relaxes back out when the gene stops). Because a cell's welds are sized to its radius, a
contracting cell tugs its neighbours — and a *travelling wave* of contraction across a body produces
locomotion (it swims). Contract is the muscle.

```
Break rg : rg > 0 : Contract              # flex
```

A single always-on Contract just shrinks a cell. Movement needs the contraction to be *rhythmic and
phased* across the body — which is exactly what a [biological clock](#11-biological-clocks) gives
you.

There is deliberately no "Expand": a cell larger than its biomass baseline would coarsen the whole
world's collision grid. Contraction alone is enough for locomotion.

### Lyse — predation

`Lyse` tears biomass off a **touching, un-welded** cell and assimilates what the attacker can digest;
anything indigestible is dumped to the environment. The efficiency gear sets the predator strategy:
low gear = a brutal shredder (lots of damage, most spilled), high gear = a surgical digester (less
damage, nearly all consumed). It's how carnivore lineages and an arms race appear.

```
Break rg : Touching > 0 : Lyse @15        # when bumping prey, digest it carefully
```

---

## 9. Diffusion and the three roles a molecule can play

This section is the conceptual key to clocks and morphogens, so it's worth slowing down.

A cell only exchanges (with the environment and with welded neighbours) the species it can
**metabolise or make** — its *metabolic reach*, derived from its genome. Crucially:

- A species a gene **breaks, converts, or imports** is *in flux*: it diffuses to welded neighbours.
- A species a gene only **synthesises with FormBond and never consumes** is held but **never
  shared** — it stays *inside the cell*. This is cell-private memory.
- A species a gene merely **senses** in a condition (`Chem`/`Conc`) is **not** thereby made
  transportable. **Sensing is not a channel.** A gene can read a morphogen to make a decision without
  that reading causing the morphogen to leak away.

Those rules give three roles a molecule can play, all determined purely by how your genome uses it:

| Role | Genome signature | Behaviour | Used for |
|---|---|---|---|
| **Metabolite** | broken / converted / imported | diffuses, taken up, retained | food and structure |
| **Morphogen** | made at a source *and* consumed everywhere | diffuses *and* is eaten → forms a **gradient** | positional information (shape) |
| **Determinant** | synthesised (or seeded by division) + sensed, **never** consumed | held but **intracellular** → isolated, permanent | committed fate / memory |

Keep this table in mind. The difference between "a signal that spreads out into a gradient" and "a
private latch that marks one cell forever" is *nothing more than whether some gene also consumes it*.

---

## 10. Walkthrough: the autotroph, line by line

The seeded `Autotroph` is the simplest complete creature — it feeds on light, grows, and buds off
copies. Here it is:

```
Light : rg < 3000 : FormBond r g          # (1) production
Light : Biomass < 3000 : Convert rg        # (2) growth
Break rg : Biomass > 2000 : Divide sever  # (3) reproduction
```

- **(1) Production.** Under light, join `r` + `g` into `rg`, topping up the cytoplasm reserve to
  3000. The `r` and `g` monomers arrive for free by passive uptake from the environment. Each `rg`
  bond formed banks one quantum of energy.
- **(2) Growth.** While the cell is still small (biomass < 3000), lock `rg` from cytoplasm into
  biomass. The cell gets bigger. Note this gene *stops* at biomass 3000 — it doesn't grow forever.
- **(3) Reproduction.** Once biomass passes 2000, break the stored `rg` (cashing its energy) to fund
  a division, and `sever` so the daughter buds off as a fresh single cell to colonise elsewhere.

Notice the reserve logic: production tops the reserve to 3000, growth stops at 3000, but division
triggers at 2000 — so there's always a stockpile of `rg` on hand to *pay* for the division when it
fires. That interplay of thresholds is the essence of authoring in Cyto: you're tuning a handful of
numbers so that the right gene has fuel at the right moment.

Compare the `Heterotroph`: it has **no `Light` gene at all**. It lives entirely off `rg` already in
its cytoplasm — received by diffusion from autotroph neighbours or from its starter reserve —
breaking `rg` to grow, divide, and repair. When the `rg` runs out it starves and recycles its matter
back to the environment. That closes the food web: light → `rg` (autotroph) → diffusion/death →
biomass (heterotroph).

Two starter genomes accompany this tutorial in the library, `Tutorial-1-Grower` (production + growth
only — watch a *single* cell grow and plateau, no division to distract you) and
`Tutorial-2-Coloniser` (adds a non-severing `Divide`, so it grows into a *welded colony* rather
than budding off singles). Paint them, click them, and read the genes against the notes above.

---

## 11. Biological clocks

A clock is a gene circuit whose state changes on a predictable schedule. Cyto gives you two kinds,
and they map onto SimulifeHub's "timer" gene and its oscillators respectively.

### 11a. The dilution timer (a one-shot clock, for free)

This is the single most elegant timekeeping trick in Cyto, and it needs **no oscillator at all** —
just `Conc`.

Recall `Conc(m) = count(m) / body size`. Now suppose a cell receives a fixed dose of a determinant
`m` at birth (e.g. handed to it by an asymmetric division) and **never makes more**. As the cell
*grows*, its size climbs while the `m` count stays fixed — so `Conc(m)` steadily *falls*. A gene
gated on `Conc(m) < threshold` therefore fires **after a predictable amount of growth**: a timer,
with no decay machinery and no counter.

```
# 'm' is a determinant: made once (or seeded at division), never consumed → it just dilutes as we grow
... : Conc(m) < 200 : <do the thing after the cell has grown enough>
```

This is exactly SimulifeHub's timer (start a countdown; when it elapses, act), but it falls out of
concentration-vs-size for nothing. It's the natural way to make a fate "happen a while after
division".

### 11b. The ring oscillator (a repeating clock)

For a *rhythm* — something that fires over and over, e.g. to drive a swimming stroke — you build a
**feedback loop of morphogens that chase each other**. The pattern: a handful of morphogen species,
each gene making the *next* species in the ring and gated on the *previous* one, so concentrations
rise and fall in a rotating sequence. Because each phase represses/succeeds the next with a delay
(the time it takes to build up a species), the whole thing cycles.

Your library already contains several working oscillators — the `.gene` files next to the game named
`cyto-genome-simple-clock`, `cyto-genome-metabolic-clock`, `cyto-genome-5-clock`, etc. Here's the
shape of `simple-clock`, annotated:

```
Break rr : Biomass < 2400 : Convert rr @15        # growth/housekeeping (keeps the cell alive)
Light  : Conc(r) > 0 : FormBond r r                # production: make the fuel species 'rr' under light
Break rr : gg < 220 & gb < 200 : FormBond g g      # phase A: when gg and gb are both low, make gg
Break gb : gg < gb & gg < 200 : FormBond g r       # phase B: gg high & gb low → make gr...
Break gr : gb < gr & gb < 200 : FormBond g g        #   each phase's product becomes the next phase's gate
Break gg : gr < gg & gr < 200 : FormBond g b       # phase D
Break gg : gr < gg & gr < 200 : Contract @15        # the actuator: contract only during one phase of the ring
```

Read the middle four genes as a cycle: each one is gated on "the previous morphogen is now high, the
next one is still low", and its job is to build the next morphogen. That hands the baton around the
ring endlessly. The **last gene is the payoff** — a `Contract` gated on one specific phase of the
ring, so the cell contracts *rhythmically*, in time with the oscillator. In a welded body where the
phase is slightly offset cell-to-cell (because the morphogens diffuse), that becomes a *travelling
wave* of contraction — and the organism swims.

The design principles, distilled:

- **Delay + negative feedback = oscillation.** A species that (eventually) shuts off its own
  production, with a lag, will overshoot and undershoot rather than settle — it rings. A ring of
  three or more such species gives a clean rotating phase.
- **Keep the morphogens as pure signals.** In the clock genomes the phase species (`gg`, `gb`,
  `gr`, …) are made and broken by the ring itself — they're *morphogens* in the §9 sense, spreading
  cell-to-cell so the whole body shares (a slightly phase-shifted) beat.
- **`@15` (high efficiency gear) throttles the rate.** A high gear caps how much a gene does per
  tick, which slows and stabilises the oscillation — it's your period/tempo dial. Tune it to change
  the swim frequency.
- **Gate everything tightly.** Every clock gene is gated so it's active only during its phase —
  otherwise the bloat tax (§7) starves the whole circuit.

Load one of the clock genomes, click a cell, and watch the cytoplasm counts in the info panel tick
up and down in sequence. That's the clock running. Then watch the cell pulse.

---

## 12. Morphogen gradients

A gradient is how a cell knows *where it is*. A localised **source** makes a morphogen, it
**diffuses** outward through the welded body, and every cell **consumes** it (the sink) — so its
concentration is high near the source and falls with distance. A cell reads its own `Conc` of the
morphogen and thereby knows how far it is from the source. This is the "French flag" of developmental
biology, and it's buildable in Cyto today.

### 12a. The source–diffuse–sink recipe

With a primary molecule `P` (the cell's energy currency) and a morphogen `M`:

```
# SOURCE — only in the founder/organiser cell (gated on a determinant X that marks it):
Break P : <X present> : FormBond ... -> M         # spend P to synthesise M

# SINK — in EVERY cell (this consumption is the decay that keeps the gradient from flattening):
Break M : <M present> : FormBond ... -> P          # metabolise M back to P

# Because M is *consumed* (the sink Breaks it), it counts as a metabolite → it diffuses cell-to-cell
# for free. Source + free diffusion + distributed sink = a steady-state gradient.
```

The sink is the non-negotiable part. Without something eating `M` everywhere, diffusion alone just
smears it evenly and there's no gradient. The rate of consumption sets the gradient's length scale —
faster sink (lower gate threshold, or a higher efficiency gear capping the source) = a steeper,
shorter-range gradient. That efficiency gear on the source `FormBond` is your "how far does this
morphogen reach" dial.

### 12b. Seeding the source: asymmetric division and the determinant

For a gradient you first need *one* cell to be the source and the rest not to be — symmetry has to be
broken. That's what **asymmetric divide** (§8) is for. The founder divides with
`Divide X`, handing a determinant `X` whole to one daughter. Only cells carrying `X` run the source
gene, so the morphogen is made in one place.

`X` is a **determinant** (§9): synthesised or seeded, sensed, but **never consumed** — so it stays
*intracellular* and permanent. It doesn't spread; it marks a lineage. `Divide X mother` keeps the
source centred (radial body plan); the default (daughter) rides it to the edge (axial body plan).

The killer property, from §9: because *sensing isn't a channel*, a fate gene can gate on `Chem(X)` or
`Conc(M)` to differentiate **without** thereby causing `X` or `M` to leak away. The asymmetry a single
division establishes therefore *persists* across the whole colony — one genome, many stable cell
types.

### 12c. Reading position: `Conc` bands (the French flag)

Once the gradient exists, a cell reads its position with a concentration **band** — two clauses
bracketing a window:

```
600 < Conc(M) :               ... : <"I'm near the source" → core tissue>
200 < Conc(M) & Conc(M) < 600 : ... : <"I'm in the middle ring" → flesh tissue>
Conc(M) < 200 :               ... : <"I'm far out" → skin tissue>
```

Three bands, three tissues, from one diffusing signal. Each band's action might commit a determinant
(a self-sustaining fate latch), change colour by building a different biomass molecule, or switch
whether the cell divides — that's differentiation driven by position.

### 12d. Shaping the body: oriented division

A gradient also gives you a *direction* (which way it points), and `Divide ... along/across <M>`
uses it: dividing **along** the gradient extends a **thread/limb**; dividing **across** it widens a
**sheet/layer**. Consistently-oriented divisions elongate a structure; mixing orientations fills out
a blob. This is how you get shapes that aren't just round clumps.

The library's `Swimmer` and `hungry` genomes both use these together — look for lines like:

```
Break rb : bb > 0 & br < 10 & Biomass > 2000 : Divide bb mother across gr
Break rb : br > 3000 & gr < 5 & Biomass > 3999 : Divide gr mother sever along gr
```

The first widens the body into a sheet (`across`), keeping the morphogen `bb` centred in the mother;
the second, under different chemical conditions, buds a new founder off (`sever`) along the axis.
Click a `Swimmer` cell and read its full genome against this section — it's a real, evolved worked
example of everything here.

### 12e. Honest caveats

Building a gradient body is the hardest thing in Cyto, and the design notes are candid about why:

- **Signalling circuits don't emerge on their own.** A source nobody reads, or a reader of an absent
  signal, is useless — selection can't climb to them one step at a time. You have to **hand-author**
  the circuit first (the "hopeful monster"), *then* let selection refine it. Turn mutation **off**
  while you build.
- **Physics is noisy.** A jiggling, growing, dividing colony is a much harsher substrate for a
  reaction-diffusion gradient than SimulifeHub's frozen hex grid. Calm the physics while you crack
  shape: keep contraction/locomotion and Lyse out of the genome until the gradient itself is stable.
- **Integer diffusion truncates the tail.** Diffusion moves `⌊count/(neighbours+1)⌋` per step, which
  floors to zero once a species gets thin — so a morphogen stops spreading past a certain distance.
  Keep source counts high (or the gradient short) so the readable range covers the body.
- **The body must stay welded.** Morphogens only diffuse across welds, so a body that frays loses its
  gradient. Budget a `Repair` gene — cohesion and morphogenesis are tied together.

---

## 13. Experiments to try

Roughly in order of difficulty:

1. **Watch one cell grow.** Paint `Tutorial-1-Grower` in a bright spot. Click it and watch biomass
   climb and plateau. Move it into shade and watch it stall.
2. **Colony vs budding.** Paint `Tutorial-2-Coloniser` (welds into a mass) next to `Autotroph`
   (buds off singles). Same growth, different `Divide` — see how one option changes the body.
3. **Feed a heterotroph.** Grow an autotroph colony, then paint a `Heterotroph` next to it. Watch it
   live off the leaked `rg`, and starve if you move it away.
4. **Tune the clock.** Load `simple-clock`, click a cell, and change the `@15` on the `Contract` gene
   to `@10` in the editor. Watch the swim tempo change.
5. **Build a dilution timer.** Take the coloniser, add an asymmetric `Divide m` and a gene gated on
   `Conc(m) < 200` that changes colour (converts a different molecule). Watch a fate appear a fixed
   time after each division.
6. **Author a two-band gradient.** The full §12 build — source in a founder, sink everywhere, two
   `Conc(M)` bands driving two different `Convert` actions so the body visibly stripes. This is the
   summit; expect to spend real time tuning thresholds.

Save your experiments with EXPORT so they become palette swatches you can paint again.

---

## 14. Troubleshooting

**"My gene isn't doing anything."** Click the cell and check, in order: (a) is the **condition**
actually true right now? (read the cytoplasm counts in the panel); (b) does the **energy source**
have fuel? — a `Break rg` gene does nothing with no `rg`; a `Light` gene does nothing in shade; (c) is
the **bloat tax** starving it? — too many always-on genes leave each a tiny slice; (d) for `FormBond`,
does the product **repeat a bond** (silent no-op) and do the **reactants exist**?

**"It grows forever / never divides."** Growth genes should be gated to *stop* (`Biomass < N`), and
the divide gate (`Biomass > M`) needs `M` below where growth plateaus, with a reserve to pay for it.
Re-read the threshold interplay in §10.

**"My colony falls apart."** No `Repair` gene, or it's starved. Welds accumulate damage and there's
no free healing.

**"My morphogen won't form a gradient."** Almost always the **sink** is missing (nothing consumes it,
so it smears flat) or the **source isn't localised** (no determinant gating it, so every cell makes
it). Check §12a/§12b. Also confirm the body stays welded.

**"My careful genome mutated into nonsense."** The **Mut** button was on. Turn it off while
authoring.

---

## 15. Grammar cheat-sheet

```
GENE  ::=  <source> : <condition> : <action> [@<gear>]

SOURCE  ::=  Light
           | Break <bond>              # e.g. Break rg

CONDITION  ::=  <clause> [ & <clause> ]*
CLAUSE     ::=  <operand> (< | >) <operand>
OPERAND    ::=  <int>                  # constant
              | <species>              # cytoplasm COUNT of that molecule (Chem)
              | Conc(<species>)        # size-normalised concentration
              | Biomass                # total biomass (bond count)
              | Touching               # count of un-welded cells in contact

ACTION  ::=  Import <species>          # one-way inward channel (also hoards vs gradient)
           | Export <species>          # one-way outward channel (secrete / dump)
           | FormBond <a> <b>          # join molecule ending 'a' + one starting 'b'
                                       #   *a = wildcard "ends with a"; a* = "starts with a"
           | Convert <species>         # lock into biomass (grow)
           | Contract                  # flex radius inward (muscle)
           | Divide [<morphogen> [mother|daughter]] [sever] [along|across <axis>]
           | Repair                    # heal welds / adhere touching cells
           | Lyse                      # tear biomass off touching prey

GEAR  ::=  0 .. 16                      # efficiency: higher = more ops per quantum but a lower per-tick cap

# '#' begins a comment. Blank lines ignored. Atoms are r, g, b.
# Header lines a .gene file may carry:  # genome: <name>   and   # color: <rrggbbaa>
```

**Key numbers** (defaults; they evolve under mutation): chemistry scale ≈ 1000; a cell dies when
biomass falls below ≈ 1000; `Conc` is scaled so a value of 1000 ≈ one molecule per biomass-bond;
efficiency gears run 0–16.

---

*Cyto's genetics are a small language with deep interactions. You now have the whole vocabulary. The
rest is the same thing evolution does: try a circuit, watch what develops, adjust a threshold, and go
again.*
