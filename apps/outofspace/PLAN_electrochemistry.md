# Electrochemistry, and the electrolyzer that stops being a special case

Status: **scoped, nothing built** (2026-09-06). Successor in spirit to `PLAN_unified_reactions.md`,
which made one reaction shape swept by one pass. This adds the **second condition axis** — a row
today fires because a tile is hot enough, and after this a row may instead fire because a cell is
*charged* enough.

⚠️ **Interleaves with `PLAN_power_network.md`**, which was split out of this one on 2026-09-06. That
plan's network lands between increments 1 and 2 here; see its §7 for the combined order and §5 for
why this plan's cell is the load it has to be designed against.

> A reaction is a fact about matter and conditions. Temperature is not the only condition, and
> `onsetKelvin` being the only one is why the game has a machine that performs its own chemistry.

## 1. The prompt for this

`Electrolyzer.kt` spends thirty lines apologising for existing. It is worth quoting, because it is
this plan's entire justification:

> ⛔ **Why it performs its reaction instead of hosting one** … a deliberate departure from the
> principle [Furnace] is built on — *machines control conditions, chemistry does the work*.

Both of its stated reasons are the same reason wearing two hats:

- *"It has no onset temperature… there is no condition a machine could create that would make a
  `REACTIONS` row fire here and nowhere else."*
- *"Its products recombine instantly"* — because the only thing separating them is that the machine
  hand-places them in two different stores.

Neither is a fact about electrolysis. Both are facts about `Reaction` having exactly one gate.
Give it a second one and the machine becomes a condition-provider like every other machine, the
hand-written split is deleted, and **water electrolysis stops being written down anywhere** — it
becomes what happens when nothing in the cell is easier to reduce than water is.

## 2. Why this and not more reduction rows

Copper is already reachable: `4 CuFeS₂ + 13 O₂ → 4 CuO + 2 Fe₂O₃ + 8 SO₂` at `UnifiedReaction.kt:1065`,
then `CuO + C → Cu + CO` at `:1078`. So an electrowinning route that ends in the same metal has to
be worth building for a reason other than the metal. There are four, in ascending order of how much
they change:

1. **It runs cold.** Leach and plate happen near ambient; the roast-and-reduce route needs 1500 K and
   a carbon supply. That is a different cost curve for the same product — heat traded for potential —
   and the first genuine *alternative* in the refining tree rather than an extension of it.
2. **It is selective where a roast is not.** That chalcopyrite row hands back copper oxide and
   hematite **mixed**, because chalcopyrite is half iron by formula. Acid takes the tenorite and
   leaves the hematite; a furnace takes both and hands back a copper-iron alloy.
3. **The electrochemical series is a second ladder.** It orders the metals differently from the
   carbothermic one, and — see §5 — it has a hard ceiling that falls out of the physics rather than
   being imposed.
4. **⭐ It is the first process in the game with a *cycle* in it.** Every circuit built so far is a
   line: ore in one end, product out the other, reagents consumed. A leach circuit regenerates its
   own acid at the anode and returns it to the leach tank, so what the player builds is a **loop that
   only needs topping up**. That is a new shape of build, not a new row, and it is the thing worth
   having.

## 3. What already exists and must not be rebuilt

- **`Mixture`** — grams per species, exact, immutable, with `take` as the only exact draw. A cell's
  electrolyte is one of these. Nothing new is needed to hold a solution.
- **`Saturation.kt`** — derives `condensedFraction` per tile per tick from density and temperature
  rather than storing a phase, on the argument that a cell holding two phases at once is *more*
  stable than one forced to a mean. **Dissolution is that same construction with a different curve**
  and must be built as its clone, not as a new concept.
- **`FORMATION_ENTHALPY`** — one number per species, every reaction's energy derived by Hess's law.
  The potential table is its sibling and is stated the same way, for the same reason.
- **`MINERALS`** — composition as atoms, `molarMass` derived. The ion table is the third of these.
- **The concentrator, the furnace, the rail network, `holdsBack`.** A cell is a deck machine with a
  charge and two output faces; that shape is built and tested.

## 4. Decisions taken (Stu, 2026-09-06)

1. **⛔ Ions are an oracle, never a store.** `Cu²⁺` is `Species.Copper` plus a charge that a table
   states. No ionic species are added, `Species.COUNT` does not move for them, and no `Mixture`
   grows. This is `MINERALS`' argument applied to charge.
2. **⛔ Solution lives inside a machine, never in the world.** A cell's electrolyte is a `Mixture`
   held by that cell. There is no aqueous field, no world-level electrolyte, no third store. This is
   the line that keeps the whole arc smaller than the thermochemistry arc rather than larger.
3. **Chemical batteries are deferred on a prerequisite that is now planned.** A battery is a
   *spontaneous* cell — it sources electrical energy instead of consuming it — and until
   `PLAN_power_network.md` lands there is nowhere to put that energy. ⚠️ **Not the signal network**:
   that carries verdicts, and a battery has no verdict to offer. So a battery is this plan's cell run
   backwards, waiting on a wire, and §5's three-compartment cell builds its entire substrate —
   ⭐ **a battery is a spontaneous redox reaction whose two halves are in different compartments.**
   Put Cu²⁺ and zinc metal in one mixture and copper cements out on its own, needing nothing from
   this plan; separate them and the electrons have to go the long way round. Nothing here changes to
   allow it later.
4. **Half-reactions, not whole cell reactions.** The table states couples; a cell picks one at each
   electrode and the cell reaction is *derived*. Writing whole-cell rows would reintroduce the n×m
   product this arc exists to avoid, and would make water's ceiling a special case instead of a
   consequence.
5. **Aqueous only. Molten-salt cells are a separate arc and may never happen.** See §5 — the split
   is physical, not budgetary.

## 5. The model

### One table: standard potentials

```
HalfReaction(
    oxidised: List<Pair<Species, Int>>,   // the side that gains electrons
    reduced:  List<Pair<Species, Int>>,   // what it becomes
    electrons: Int,
    standardMillivolts: Int,              // E° against the hydrogen electrode
)
```

Stated, not derived — E° needs free energy and entropy, which `FORMATION_ENTHALPY` does not carry.
It is an oracle in exactly `FORMATION_ENTHALPY`'s sense: one number per couple, textbook, and every
*cell voltage* in the game derived from pairs of them so no two can drift apart.

### One rule, and everything else is a consequence

At a cell holding a charge, applying `V` volts:

- **The cathode** runs the couple with the **highest** E° whose oxidised side is present.
- **The anode** runs the couple with the **lowest** E° whose reduced side is present, backwards.
- The cell needs `E°(cathode) − E°(anode)` volts and runs if `V` clears it.

Water contributes two couples and is otherwise unremarkable:

| Couple | E° |
|---|---|
| `O₂ + 4H⁺ + 4e⁻ → 2H₂O` | +1230 mV |
| `2H⁺ + 2e⁻ → H₂` | 0 mV (the definition) |

**That is the whole of water electrolysis.** A cell holding only water finds nothing better than H⁺
at the cathode and nothing worse than water at the anode, needs 1.23 V, and splits. Nobody writes
the row. `Electrolyzer.ENTHALPY_PER_KG`'s 484 kJ per 36 g is what the same two numbers say, and
`FormationTest`'s trick applies directly — derive one from the other and a test can say when they
disagree.

### The three payoffs, none of which is written down anywhere

**Copper beats water.** `Cu²⁺ + 2e⁻ → Cu` is +340 mV, above H⁺'s zero, so a cell holding copper
plates copper and does *not* make hydrogen. Cell voltage is 0.34 − 1.23 = −0.89 V, i.e. *less* than
splitting water. Cheaper and it wins the competition, both from the same two numbers.

**The acid regenerates itself.** The anode's only available couple is water, so it runs
`2H₂O → O₂ + 4H⁺ + 4e⁻` — and those H⁺ are the leach acid, back. The cycle in §2.4 is not a
mechanism anybody adds; it is what the anode does when there is nothing else there.

**⭐ Aluminium is impossible and nobody forbade it.** `Al³⁺ + 3e⁻ → Al` is −1660 mV, *below* H⁺.
The cathode picks hydrogen every time, forever, and the plan's scope cut enforces itself. Same for
magnesium (−2370), sodium (−2710), calcium (−2870), titanium (−1630). This is exactly why
Hall-Héroult uses molten cryolite and not water, and the game gets the reason rather than the rule.

Strictly by E°, aqueous cells reach **copper (+340), silver (+800), mercury (+850)** and nothing
else. See §8 for what that leaves out and the one honest lever for widening it.

### Dissolution, as a clone of condensation

`dissolvedFraction(species, waterMass, kelvin)` answers what share of a species in a charge is in
solution, derived per tick from a stated solubility the way `condensedFraction` is derived from the
dome. Nothing stores it. Precipitation-when-saturated then falls out the way condensation does, and
"insoluble precipitate" stops being a case anybody handles.

**A species is a candidate for an electrode only in its dissolved share.** That is the whole
coupling between §5's rule and the leach — undissolved tenorite is not at the cathode, it is sitting
in the bottom of the tank.

#### ⛔ Autoionization is NOT this shape, and pure water is the case that says so

Water makes its own ions — `2H₂O ⇌ H₃O⁺ + OH⁻` — but only to 1e-7 M, because Kw is 1e-14. ⚠️ **That
is an equilibrium constant, not a saturation limit**: nothing is undissolved and nothing has
precipitated, water simply does not *make* many ions. So it is one constant if it is ever modelled,
and it must not be bolted onto the dome above, which answers a different question.

The consequence is that **pure water barely conducts** — about 5.5e-6 S/m, against 5 for seawater
and 80 for molar sulfuric acid. Seven orders of magnitude, which is not a tuning difference but a
cell that does nothing. Every real alkaline or acid electrolyzer adds KOH or H₂SO₄ for exactly this
reason. (A PEM cell does run on deionized water, but its protons conduct through the membrane rather
than the water — the electrolyte *is* the membrane. Kept in mind for increment 3, which has a
membrane anyway.)

⭐ **Three things this buys, none of which needs a mechanism:**

- **The acid gets a second job** — leach reagent *and* the thing that makes the cell conduct.
- **A bootstrap puzzle.** No electrolyte, no electrolysis. `Halite` is in the species table and is
  abundant, so brine is the early answer and mining salt becomes a first-hour goal.
- **Brine behaves correctly for free.** At the anode, water at +1230 is *below* chloride at +1360, so
  §5's lowest-wins rule oxidises water and evolves oxygen rather than chlorine; at the cathode Na⁺ at
  −2710 loses to H⁺. **The salt carries the current and is not consumed**, which is what an
  electrolyte is, and nobody writes it down. Chlorine wins industrially only on the overpotential §8
  defers.

⛔ **Where this lands is `PLAN_power_network.md` increment 2, not here.** Conductivity is
*resistance*, and increment 1 below has a voltage dial with no current behind it — no amps, so
nothing for a resistance to attach to. Pure water splitting in increment 1 is not wrong, it is
un-modelled, and it stops working the moment the cell is a load on a real circuit.

### ⭐ 5.5 The cell has three compartments, and they are buffer tiles

**A cell is a 3×2 machine: three baths along one edge, its two terminals along the other** (Stu,
2026-09-09). Pointing right in its own frame, the anchor is the **middle bath** — the feed — with the
cathode bath behind it and the anode bath ahead of it, each on its own tile with its own rail port.
The terminals sit on the two outer tiles of the far edge, on casing that carries no port at all.

```
   terminals    T . T
   baths+ports  C F A        F = feed (the anchor)
```

⚠️ **This replaced a 3×3 whose ports made a T** (Stu, 2026-09-08), which in turn replaced a 1×3. The
1×3 had a hole — it put the feed in `Inside`, which is *"the one role with no port"*
(`BufferRole.kt`), so the feed could never be delivered to. The T fixed that by putting the feed on
the stem and letting it fall into the middle bath, at the cost of two mechanisms. **The 3×2 fixes it
by geometry instead**, and is the shape `PLAN_machine_relocation.md` increment 0 was built to make
expressible: before it, every footprint was odd on both axes by construction.

#### ⭐ Three mechanisms the 3×2 deletes

1. ⛔ **No generalisation of `Storage`'s exception.** The T needed *"a machine declares which store
   its input port fills"*, because its feed port was on the stem and the bath it filled was in the
   middle — a port whose store is not under it. Here every bath sits on its own port, so
   `BufferRole`'s *"a store sits on the port it serves"* holds **exactly as written**. The T section
   forbade `is Electrolyzer ->` beside `is Storage ->`; now neither is needed.
2. ⛔ **No `Inside`, so the rule it is the exception to survives untouched.** The three roles are
   `Input`, `Cathode` and `Anode` — all three port-serving. `Inside` keeps meaning *"the one role
   with no port"* and the cell stops being a second machine that bends it.
3. ⛔ **No insulating segment required.** The T put a terminal on the same tile as an output port, and
   a terminal bonds the layers present at its tile (`PLAN_power_network.md` §3) — so the hydrogen
   belt sat at the cathode's potential, the oxygen belt at the anode's, and one metal rail network
   touching both shorted the cell through its own logistics. Here the terminals stand on casing with
   no port, so **nothing outside the machine is ever at an electrode's potential**. ⚠️ The insulating
   segment remains *available* and still works — one non-metal tile in a metal run is still a
   galvanic isolator made of track — it is simply no longer **forced** by the cell's own shape. See
   that plan's increment 4, which is corrected to match.

#### ⭐ Why the terminals need not touch the baths

⚠️ **Worth stating, because it is the objection the 3×2 has to answer.** The terminals are two tiles
away from the electrolyte, and rule 1 of `PLAN_power_network.md` §3 — *bodies sharing a tile always
touch* — is **gated on a terminal** for charge. So the baths are not bonded into the circuit by
sharing a tile with anything.

They do not need to be. ⭐ **A device is an edge between its two terminal nodes, and that is already
how the solve works**: `Source(positive, negative, emf, conductance)`, built for the panel at
`OutofspaceSim.kt:4871` out of nothing but `nodeUnder` at each terminal tile. The cell is the same
shape with `E` for the emf and the electrolyte's conductance for `g`. What the terminals must be is
**conducting nodes**, which they are because they stand on casing.

⭐ **And §5's parallel path survives exactly.** Current entering one terminal reaches the other either
through the electrolyte — the device edge, which does the work — or around through the casing along
the far edge. A copper-cased cell still shorts around its own chemistry and does nothing but warm up.

#### ⛔ Three baths, and the middle one is where deliveries land

**Each of the three is a bath** (Stu, 2026-09-08) — one at each electrode, and one **directly
between them**. The input port delivers into the middle bath, *if there is room in it*.

⭐ **"Directly between them" is what makes ion migration a spatial statement**, and the 3×2 keeps it
intact: the three baths are still a line with the feed in the middle, so cations drifting toward the
cathode and anions toward the anode is a movement from the middle to each end rather than a
bookkeeping entry between two stores that happen to share an owner.

⛔ **`Cathode` and `Anode` must be new `BufferRole`s, not reused `Product`/`Waste`.** That file added
`Oxidiser` rather than let a rocket's tank read WASTE, on the grounds that *"this codebase deleted
`Material` and `MachineKind` rather than live with a name that lies."*

**What the compartments buy, and it is the reason for them:**

- **Acid at one end, base at the other, from the electrode reactions themselves.** The anode runs
  `2H₂O → O₂ + 4H⁺ + 4e⁻` and turns the anolyte **acidic**; the cathode runs `2H₂O + 2e⁻ → H₂ + 2OH⁻`
  and turns the catholyte **basic**. Drain one end and you have concentrated acid; drain the other
  and you have caustic. From a salt and a potential, and nobody writes it down. This is
  water-splitting electrodialysis, and chlor-alkali is its industrial giant.
- **The cell can be flushed.** Drain all three, refill with fresh water, start again — which is what
  makes a fouled or exhausted electrolyte a recoverable situation rather than a stuck machine.
- **⭐ It is a battery's substrate.** See decision 3. Two compartments of differing composition
  separated by something *is* a galvanic cell, and its voltage comes off the same table with the sign
  flipped.

**One new mechanism, and only one: ion migration.** Under the applied field, cations drift toward the
cathode compartment and anions toward the anode, at a rate set by the current. Everything else is
§5's competition rule run at each end.

⚠️ **A consequence worth stating**: once compartments hold arbitrary mixtures, spontaneous redox
inside *one* of them becomes reachable **with the power off**. Copper cementing onto zinc is a
legitimate `Reaction` row needing no voltage at all. That is not a bug, but the cell stops being
inert when unpowered, which today's electrolyzer is.

#### ⛔ A bath states a volume, and the volume does ONE job

> ⚠️ **CORRECTED 2026-09-09, by measurement.** This section argued for two jobs and called the pairing
> *"the test that it is the right number"*. **The second job does not exist.**
> `condensedDensityAt(293 K, …)` returns **null** for hydrogen and for oxygen: both are above their
> critical temperature at room temperature and cannot be liquid at any pressure. So "the bath is full
> of it" is undefined for exactly the two species a cell exists to make, and a supercritical gas in a
> fixed volume has no capacity short of close packing — the pressure simply rises.
>
> ⛔ **So the appetite stays on a mass cap** (`Electrolyzer.BUFFER_CAP`) and the volume answers the
> **phase** and nothing else (Stu, 2026-09-09). A gas bath that ever wants a capacity wants a
> *pressure ceiling*, which is a mechanism and a balance dial rather than a derivation, and it is not
> in increment 1b. Pinned by `a cell's two gases have no liquid density to be full of` in `BathTest`,
> so the day one of them gains a condensed density at ambient, this argument reopens loudly.
>
> What survives below unchanged: **derive the volume, never state it.**

#### The argument as it was written

Agreed (Stu, 2026-09-08). §5.6 needs it so the contents have a **phase** — `FluidPhase` comes off
reduced density and temperature (`StateEquation.kt:672`), which is a question about matter in a
volume, and a `BufferLayer` store is a `Mixture` with none. And the input port needs it to know when
there is *"enough space"* to accept a delivery, which is `sinkAdmits` and nothing new: ⛔ **demand is
acceptance, one door** — see `project_oos_economy`.

⭐ **One number answering both is the test that it is the right number.** A bath that is full refuses
deliveries *and* holds its gas at a density that decides the phase, and neither reading is free to
drift from the other. ⛔ Derive it from the tile and the machine's `DeckMachineKind.fillPermille` — a
stated litres-per-bath constant is the version of this that quietly becomes a fudge.

⚠️ **This section used to cite `Body.capacity` as the precedent and that citation was wrong.**
`Body.capacity` is *"millijoules/kelvin, per tile"* (`Body.kt:92`) — a **heat** capacity, not a
volume. The volume precedent is `TILE_LITRES` (`Composition.kt`, and `StateEquation.kt:212` for the
mass-per-tile form), which is what a tile's own phase question is already answered from. The shape of
the argument was right and the function named was not.

It is not an internal grid anybody has to build — `BufferRole.kt` already says what this costs:

> ⚠️ **Adding one is cheap and stays cheap** … a role costs a distinct tile of the machine's own
> footprint and nothing else. A 3×3 has nine and the rocket uses three.

`BufferLayer` is keyed by **tile**, not by (machine, role), and the placement rule is already the one
this wants: *"a store sits on the port it serves"*, with `Inside` the one role having no port and
taking the centre. So the feed is `Inside` at the centre tile and the two ends are port-serving
roles. ⛔ **They must be new roles, `Cathode` and `Anode`, not reused `Product`/`Waste`** — that file
added `Oxidiser` rather than let a rocket's tank read WASTE, on the grounds that *"this codebase
deleted `Material` and `MachineKind` rather than live with a name that lies."*

**What the compartments buy, and it is the reason for them:**

- **Acid at one end, base at the other, from the electrode reactions themselves.** The anode runs
  `2H₂O → O₂ + 4H⁺ + 4e⁻` and turns the anolyte **acidic**; the cathode runs `2H₂O + 2e⁻ → H₂ + 2OH⁻`
  and turns the catholyte **basic**. Drain one end and you have concentrated acid; drain the other
  and you have caustic. From a salt and a potential, and nobody writes it down. This is
  water-splitting electrodialysis, and chlor-alkali is its industrial giant.
- **The cell can be flushed.** Drain all three, refill with fresh water, start again — which is what
  makes a fouled or exhausted electrolyte a recoverable situation rather than a stuck machine.
- **⭐ It is a battery's substrate.** See decision 3. Two compartments of differing composition
  separated by something *is* a galvanic cell, and its voltage comes off the same table with the sign
  flipped.

**One new mechanism, and only one: ion migration.** Under the applied field, cations drift toward the
cathode compartment and anions toward the anode, at a rate set by the current. Everything else is
§5's competition rule run at each end.

⚠️ **A consequence worth stating**: once compartments hold arbitrary mixtures, spontaneous redox
inside *one* of them becomes reachable **with the power off**. Copper cementing onto zinc is a
legitimate `Reaction` row needing no voltage at all. That is not a bug, but the cell stops being
inert when unpowered, which today's electrolyzer is.

### ⭐ 5.6 An output ships a **sample** of its compartment, not a product the machine chose

⭐ **This is the change that closes the electrolyzer's stated departure from the house rule.**
`Electrolyzer.kt` admits it is *"a deliberate departure from the principle [Furnace] is built on —
machines control conditions, chemistry does the work"*, because it performs its reaction and sorts
the results into two clean stores. Under sampled outputs it stops sorting: the machine creates
conditions in two compartments and each port ships **what is actually standing in the compartment
behind it**. For water at the cathode that is hydrogen, water, hydroxide and whatever salt is in the
bath. Nobody writes down what comes out of which mouth; the chemistry and the geometry do.

⛔ **The sample is GAS PHASE ONLY, and that is narrower than it first looks.** The obvious rule —
*take gas and undissolved solid, leave the water and the dissolved ions* — cannot be written, because
**nothing in the game knows what "dissolved" means.** `Cell.kt:105` states it: telling deposited
copper from dissolved copper is *"a charge this game does not store. That is `dissolvedFraction` and
it is increment 3's."* `AQUEOUS_ELECTROLYTES` is a set of two species and `electrolyteStrength` a
mass fraction of the whole charge — neither is a per-gram distinction. So a solid-phase rule cannot
tell brine from salt crystals, and it would ship the bath's own halite out of the machine, which is
the exact opposite of leaving the bath alone.

⭐ **Gas-only is also the more physical rule.** Electroplated copper belongs *on the electrode* until
something scrapes it off; it has no business riding out on a gas belt. So the narrower rule loses
nothing that should have been shipped, needs no new concept, and leaves `dissolvedFraction` where the
increment table already has it — **3c, after this**.

⚠️ **The gas question needs a volume, and a buffer has none.** `FluidPhase` is derived from reduced
density and temperature (`StateEquation.kt:672`), so it is a question about matter in a *volume* —
`Scavenge.kt:39` notes a tile answers `Solid` only when *"packed to the solid branch, some four and a
half tonnes of water."* A `BufferLayer` store is a `Mixture`: mass and energy, no volume. So a
compartment must state what volume it is before its contents have a phase. ⛔ Derive it from the
tile and the machine's `fillPermille` as `Body.capacity` already does — do not invent a constant.

⚠️ **Draw it with `Mixture.take`'s discipline.** A phase split is a partition, not a per-species
scaling, but the heat must leave in proportion with the matter it belongs to — see
`reference_oos_microgram_deadlock`, and `Mixture.take` is the only exact draw.

### ⛔ 5.7 Sampled outputs make the products BLENDS, and that is a regression until a separator exists

Today the electrolyzer ships `made.cathode` and `made.anode` — **pure** hydrogen and **pure** oxygen.
A sampled gas port ships hydrogen *and the water vapour standing above a warm bath with it*. That is
a blend, and the size of the contamination is not the point:

- ⛔ **Packets never merge across that line.** Pure and pure of one species merge; blend and blend
  merge; **pure and blend never do.** So a blend arriving at a tank holding pure hydrogen does not
  join it, and a packet that cannot be placed stands where it is.
- ⚠️ **`PLAN_chemical_rockets.md` is built and complete, and its exhaust velocity comes off molar
  mass.** Water vapour in the hydrogen raises the mean molar mass and drops `v_e`. That is a correct
  and rather good consequence — and it is a silent downgrade of working content if it arrives before
  the player has any way to undo it.

So: **the phase separator lands in the same commit as the sampled outputs, or the outputs stay pure
until it does.** Stu named the separator as probably-needed; this is the argument that it is not
optional and not orderable afterwards. ⚠️ It is the one piece of new scope this section adds, and
sizing it is 3b's first question rather than a thing to discover halfway through.


## 6. ✅ DECIDED (Stu, 2026-09-06) — the species budget

**Option A: one species per anion family.** `Sulfate` (SO₄, 96), `Hydroxide` (OH, 17) and
`SulfuricAcid` (H₂SO₄, 98). +3, leaving 15 of the 18 slots before `StuffLayer`'s three-word presence
bitmask grows a fourth.

`CuO + H₂SO₄ → Cu²⁺ + SO₄²⁻ + H₂O`. The copper is `Species.Copper` by decision 1. The sulfate had no
home, and now it has one.

### What was rejected, and the argument that settled it

- **B. Solutions hold elements only** — copper, sulfur, oxygen, hydrogen, speciation derived. +1, but
  nothing distinguishes sulfate from sulfite and a leach cannot say which acid it used.
- **C. Metal salts as species** — `CopperSulfate`, `ZincSulfate`… the n×m table this arc exists to
  avoid.
- **D. Throw away every ionically bonded species and keep only ions**, deriving a salt as a *reading*
  over a set of them. Conceptually blurrier but more physically honest in solution, and it is the
  only alternative that would have replaced the foundations rather than added to them.

  ⛔ **Rejected on physics, by Stu.** *Mixing two salts does not let them swap partners.* Dissolving
  them and driving the water off does — and the crystal that forms is a **lower-energy locked
  state**, not a bookkeeping choice about which cation sits next to which anion. A salt is therefore
  not a bag of ions, and a model that says it is would let a vessel transmute halite and gypsum into
  each other by pouring them into the same hopper. The distinction between dissolved and crystalline
  is real, and it is exactly what the ion-only model cannot express.

### ⚠️ These are the first ion-only species, and that is a new category

`SulfuricAcid` is ordinary — neutral, real, liquid at 283 K, 1830 kg/m³, shippable. **`Sulfate` and
`Hydroxide` are not.** Neutral SO₄ does not exist and neutral OH is the hydroxyl radical, a transient
nothing puts on a belt. They are the first entries in the table that **cannot exist uncharged**.

⛔ **This does not breach decision 1**, and the distinction is worth stating because it is the whole
difference between n+m and n×m: decision 1 forbids a charged *duplicate* of a species that already
exists (`CopperIon` beside `Copper`), which is what makes every salt want to be a species too. This
adds a **group the table has never had**, which happens only to exist charged.

### ⛔ Why they cannot be a standalone commit

Measured 2026-09-06, before writing a line. **Five guard tests hold over every species**, and they
exist precisely to keep invented numbers out of the table:

| Test | Demands |
|---|---|
| `MineralTest.everySpeciesHasPhysicalConstants` | molar mass, specific heat, **and a solid density** |
| `SpeciesPropertyTest.nothing was left without a conductivity or a melting point` | both > 0 |
| `SpeciesPropertyTest.every conductivity is inside the range real solids occupy` | 20 ≤ k ≤ silver |
| `PricesTest.every species has a price` | `listPrice > 0` |
| `PricesTest.every element occurs in some rock` | a `MINERALS` composition **or** rock abundance |

And one that closes a loop: `MineralTest.everyMineralIsMinedOrMade` requires a `MINERALS` entry with
zero abundance to be the product of some `REACTIONS` row. Sulfate must be in `MINERALS` to be
priced; once there it must be *made*; and the thing that makes it is the leach.

⭐ **So the species land with increment 3 and not before.** Added alone they take the build red, and
that is the table's guards working rather than an obstacle to route around.

### ⛔ How the guards are satisfied: a host crystal, not an exemption

The obvious move is to exempt ion-only species from those five tests. **Do not.** They are what keeps
the table honest, and an exemption list punches the hole in exactly the wrong place — the same
argument that deleted `Material` rather than live with a name that lies.

Instead **borrow real constants from a named host crystal**: sulfate takes barite's density, melting
point and the existing `K_SULFATE` class estimate; hydroxide takes a hydroxide mineral's. Documented
as *what the group answers inside a real crystal* — traceable rather than invented, and every guard
stays intact.

Paired with an **ion-only declaration** in `Fluid.kt`'s idiom — a stated set, and a test that neither
species ever appears in a cargo layer, the atmosphere, a rock or a construction site. ⚠️ That test is
the one that makes the borrowed constants safe: they are only harmless because nothing can ever hold
a lump of sulfate for them to describe.

## 7. Increments

Ordered so the case that decides the design comes first. **Each increment is one commit on `main`**,
green before it lands.

### Increment 0 — the guard ✅ BUILT (2026-09-06)

A test that walks every `HalfReaction`, asserts its species exist, and derives known cell voltages
from pairs — Daniell at 1.10 V, water at 1.23 V, copper electrowinning at 0.89 V — against stated
figures. `FormationTest`'s argument: a potential nobody can check is a potential that is eventually
wrong, and a wrong one is invisible because the row still balances.

Cheap, and it is the guard that has to survive to the end.

### Increment 1 — the potential axis, on water alone ✅ BUILT (2026-09-06)

No leaching. No metals. No new species. Add `HalfReaction`, the two water couples, the competition
rule, and turn the electrolyzer into the `Cell`.

⚠️ **Voltage is a dial on the machine, not a wire.** This increment is deliberately power-agnostic —
`PLAN_power_network.md` inc 2 replaces the dial with a terminal, and that is one line. Building the
chemistry first is what gives that plan a **threshold load** to be designed against; see its §5.

### ⚠️ The machine survives. Its name and its hard-coded reaction do not.

**The cell *is* the electrolyzer** — same footprint, same inlet at the back, same two output faces,
because a cathode and an anode wanting separate stores is what that second face was always for. What
increment 1 deletes is the machine's private copy of `2 H₂O → 2 H₂ + O₂`, its `ENTHALPY_PER_KG`, and
its hand-placement of the two products. Those become a table lookup and a competition.

The **rename is the point, not tidiness**: after this the machine does not electrolyze, it applies a
potential, and what happens is whatever its charge and voltage permit. `Electrolyzer` would be a name
claiming one outcome for a machine with many — the same mistake `Decomposition`/`Oxidation`/
`Reduction`/`Combustion` were, and the same fix.

### ⛔ It gains three compartments, which reverses a decision the electrolyzer argued for

`Electrolyzer.kt` states it plainly: *"**No charge, no progress, no dwell.** … there is no `Inside`
store to hold one and no state on the machine at all beyond where it stands and what it is wired
to."* That was right for a machine that splits a stream. It is wrong for a cell, and the reason is
the whole arc: **an electrolyte is a standing bath, not a throughput.** The acid in §2.4's loop is
not consumed and not shipped — it sits in the cell, is drawn on, and regenerates. With nowhere to
sit there is no charge for `dissolvedFraction` to read and no mixture for the electrodes to compete
over.

§5.5 is the layout: `Inside` at the centre as the feed, `Cathode` and `Anode` as the two ends. ⚠️
Increment 1 needs only that the three stores exist and that each electrode writes into its own —
**ion migration and the acid/base split are increment 3's**, once there is something dissolved to
migrate.

⚠️ The runt-packet argument that decision rested on is **untouched**: the cell still ships from its
end hoppers at a rate under `holdsBack`, so the 1:8 hydrogen/oxygen split still leaves by whole
packets. The bath is upstream of that, not instead of it.

### What must be true at the end

⚠️ **Pure water splits here, and that is temporary.** There is no current model in this increment, so
there is nothing to be resistive; see §5's autoionization note. `PLAN_power_network.md` increment 2
is where a cell with no electrolyte stops drawing current and therefore stops working, and where
this test's fixture gains salt or acid. A fixture change at the moment the physics arrives — not a
moved expectation.

**Its acceptance test is `ElectrolyzerTest`, unchanged** — a cell fed water and 1.23 V makes hydrogen
at one face and oxygen at the other; below 1.23 V it does nothing; the mass ledger closes. The same
observable behaviour has to come back out of the general mechanism, which makes this a regression
target rather than a new-behaviour one — the strongest kind of first increment available here.

✅ **No save migration (Stu, 2026-09-06) — he has never built one.** So the kind is renamed rather
than aliased, and a legacy `Electrolyzer` record is *skipped, not refused*: the rule `Save.kt:1100`
already states for retired `Pipe` segments. One less thing in increment 1, on the precedent this
codebase set the last time it retired a kind.

### ⏸ Here: `PLAN_power_network.md` increments 0–2

The network, the solar panel, and the dial becoming a terminal. Nothing below depends on it — the
dial keeps working — but everything below is more interesting once a cell can be **short of power**
rather than only short of matter.

### ⏸ The rename, deferred on purpose (Stu, 2026-09-06)

**The machine stays `Electrolyzer` for now**, and the reason is better than "not yet": ⛔ **the right
name depends on an open question nobody has answered.**

Increment 1 landed the competition rule and deleted the hand-written reaction and `ENTHALPY_PER_KG`.
The rename did not, and this plan's earlier claim that the machine "becomes the `Cell`" was
over-argued — *applying a potential to drive a non-spontaneous reaction **is** electrolysis*.
Electrowinning copper is electrolysis; so is chlor-alkali. `Electrolyzer` does not lie about the
process. What it narrows is colloquial: the word usually means the hydrogen device.

⛔ **`Cell` on its own is not available.** `RigidBody.cells` is load-bearing and a body's cell walk
has its own gotchas; a machine kind called `Cell` would collide with it.

⚠️ **What decides the name is whether a battery is this same machine.** Two futures:

- **A battery is its own kind** — it has no feed, no gas outputs and no throughput, so mechanically it
  shares the half-reaction table the way `Concentrator` and `Furnace` share reaction machinery
  without sharing a kind. Then the electrolytic cell stays electrolytic, `ElectrolyticCell` is right,
  and `Electrolyzer` is merely colloquial rather than wrong.
- **A battery is this machine run backwards** — which is Stu's leaning, 2026-09-06. Then the machine
  is a *"highly versatile multi-purpose electrically wired chemical cell"* and `Electrolyzer` is
  actively wrong, because half of what it does is not electrolysis at all. The name then wants to be
  general, and the general one has to work around `Cell` being taken.

⭐ **So the rename waits on the battery decision, not on appetite.** Renaming twice would be worse
than renaming late, and thirteen compiler-checked sites is a cost that stays affordable.

### Increment 1b — the cell gains its shape and its three baths

⚠️ **This is unbuilt work sitting under a heading marked ✅ BUILT, and that is a documentation bug
this increment exists to correct.** Increment 1 shipped on 2026-09-06: it landed the competition rule
and deleted `ENTHALPY_PER_KG`. §5.5 and §5.6 were written on **2026-09-08**, two days later, and
describe compartments that do not exist — `BufferRole` is still `{ Input, Oxidiser, Inside, Product,
Waste }` and `Electrolyzer.kt` still opens *"No charge, no progress, no dwell."* So they are additions
to a closed increment and they get their own number.

**Scoped 2026-09-09 against the code.** Four steps, each one commit, green before it lands.

#### 1b.1 — the shape

`DeckMachineKind.Electrolyzer` becomes `Footprint(width = 3, height = 2, anchorX = 1, anchorY = 1)`:
the anchor is the middle bath, `behind = 1`, `ahead = 1`, `above = 1`, `below = 0`.

⛔ **The waste port's current expression breaks and must be restated, not re-anchored.** `localPorts`
reads `LocalPort(0, fp.below, …)` for the third mouth (`Port.kt:130`), and at 3×2 `below` is **zero**
— so that port would collapse onto the anchor and share a tile with the feed. The three ports become
one per bath along the `y = 0` row.

⚠️ **Existing saves hold 3×3 electrolyzer records.** `Save.kt` has no migration policy — Stu has
never built one — and the precedent for a retired shape is *skipped, not refused*. Decide which
before writing, because a silently re-anchored machine is worse than a dropped one.

Acceptance: the exact-tiles table in `FootprintTest` gains a kind that is genuinely 3×2 — the first
one — and `BufferRoleTest` still holds `localBufferOffset` and `portsOf` in agreement at every facing.

#### 1b.2 — the three baths

`BufferRole` gains `Cathode` and `Anode`. `localBufferOffset` for the cell: `Input` at the anchor,
`Cathode` at `-behind`, `Anode` at `+ahead`, all on the bath row. ⛔ No `Inside`, and no touching of
`inputBufferRole`'s `is Storage ->` branches at `BufferRole.kt:91` and `:127` — see §5.5 for why the
3×2 deletes that work rather than generalising it.

Acceptance: three stores, three ports, one per tile, no two roles on one tile at any facing —
`BufferRoleTest` already asserts exactly that for every machine and needs no new test to cover it.

#### 1b.3 — a bath states a volume

Derived from `TILE_LITRES` and `DeckMachineKind.fillPermille`. One number, two jobs: the room
`sinkAdmits` asks about, and the density `FluidPhase` is derived from. ⚠️ See §5.6's corrected note —
`Body.capacity` is a heat capacity and is **not** the precedent this wants.

Acceptance: a full bath refuses a delivery, and the same figure decides the phase of what is in it.

#### 1b.4 — the electrodes write into their own baths

Increment 1's competition rule runs per compartment: the cathode couple into `Cathode`, the anode
couple into `Anode`, the feed drawn from `Input`.

⛔ **The outputs stay PURE here.** §5.7 is explicit that sampled outputs are a regression until a
phase separator exists, and that the separator lands in the same commit or the outputs do not change.
Neither is this increment.

Acceptance: **`ElectrolyzerTest` unchanged** — water and 1.23 V makes hydrogen at one face and oxygen
at the other, below 1.23 V nothing happens, the mass ledger closes. A regression target rather than a
new-behaviour one, which is the strongest kind available here.

### ⏸ Then: `PLAN_power_network.md` increment 4 — the cell as a load

`I = (ΔV − E) / R_internal` as a `Source` between the two terminal nodes, with `R_internal` off
`electrolyteStrength` (`chem/Cell.kt:237`, which already exists). Forward above the knee, reverse
below it. ⭐ Nothing in it needs a mechanism this plan has not already put in place by 1b.4.

### Increment 2 — copper beats water

Add `Cu²⁺/Cu` at +340 mV. Feed a cell a copper-bearing charge; copper plates, hydrogen does not
appear, and the anode still makes oxygen. One new number, no new mechanism, and it is what proves
the competition rule does something rather than being a one-branch `if`.

⚠️ **The charge is handed to it, not leached.** Increment 3's job.

### ⚠️ Increment 3 is a bundle, and should not be taken whole

Assessed 2026-09-06, after increments 0–1 and power 0–2. What §6 forces is that the **species cannot
land without the leach reaction** — `everyMineralIsMinedOrMade` requires a `MINERALS` entry with no
abundance to be made by a `REACTIONS` row. Everything else below is separable and should be separated:

| | Piece | Touches |
|---|---|---|
| 3a | the three species + host-crystal constants + ion-only declaration + the leach row | the species table, five guard tests, `MINERALS`, `Prices` |
| 3b | `Cathode`/`Anode` buffer roles, the 3×3 T, sampled outputs (gas only) | machine geometry, ports, save, renderer |
| 3c | `dissolvedFraction`, and the electrolyte gate power increment 2 left unwired | `Saturation`-shaped derivation |
| 3d | ion migration and the membrane | one new mechanism |

⛔ **3a is the single largest commit in either plan** and it is the one that cannot be made smaller.
It edits the table every other system reads.

### Increment 3 — the leach, and §6's answer

`Sulfate`, `SulfuricAcid` and `Hydroxide` (§6, decided — and they land **here**, in the same commit
as the row that makes them, because the table's guards will not take them alone), `dissolvedFraction`,
and `CuO + H₂SO₄ →` as
a reaction whose reagents and products are all inside one machine's compartments. Tenorite goes in,
copper in solution comes out, and increment 2's cell plates it.

Also **ion migration and the membrane** (§5.5), which cannot be tested before there is something
dissolved to migrate: cations to the cathode end, anions to the anode end, and the membrane that
stops the two ends simply mixing back. Acid at one end and caustic at the other is this increment's
visible result and the thing §6's third species pays for.

### Increment 4 — close the loop

The anode's H⁺ are routed back to the leach tank. The player builds a **cycle**: acid → leach →
cell → acid. Topping up replaces consuming. This is §2.4 and it is the increment the arc is for; the
three before it are the machinery that lets it be built out of parts rather than declared.

### Increment 5 — selectivity

Hematite's leach rate against tenorite's, so the chalcopyrite roast's copper-iron mixture separates
in acid where it would not in a furnace. §2.2, and the first thing electrowinning does that the
existing route cannot do at all.

## 8. What this leaves open

- **⛔ Overpotential, and it is why zinc is not here.** Zinc electrowinning is industrial and real,
  and `Zn²⁺/Zn` at −760 mV says it is impossible — it works only because hydrogen evolution on a
  zinc surface is enormously slower than its potential implies. Nickel (−250), tin (−140) and lead
  (−130) sit in the same gap more shallowly. The honest fix is a stated
  `overpotentialMillivolts` per couple, which stays an oracle and widens tier 1 from three metals to
  seven. It is deliberately not in increment 2, because a fudge factor added before the strict rule
  is working is a fudge factor nobody can later remove.
- **Concentration and the Nernst equation.** Without it a cell plates at full rate down to the last
  gram and never stalls. Arguably better play; certainly less true. Defer until a cell that stalls
  as it depletes is something the build actually wants.
- **Chlorine at the anode.** `Cl₂/Cl⁻` is +1360 mV, *above* water's +1230 — so a cell fed halite
  brine should make chlorine, not oxygen, and the chlor-alkali process falls out of the same table
  for free. One row away once increment 3 lands, and it is the natural second leach.
- **Molten-salt cells**, and with them aluminium, magnesium and titanium. A furnace *and* a cell,
  and a different electrolyte model. Genuinely a separate arc; §5 explains why the seam is there and
  not somewhere chosen.
- **Anything dissolving outside a machine.** A spill that eats the deck, acid in a room, corrosion of
  hull plate. Decision 2 forbids all of it. ⚠️ The deck's own `StuffLayer` is already named in
  `PLAN_unified_reactions.md` as a gap no chemistry pass touches; this does not close it.

## 9. Explicitly not doing

- **Charging for the energy.** `PLAN_chemical_rockets.md` §1 already argued this and it does not
  change: *the energy is free, and pretending otherwise would be a lie.* ⚠️ But note what this arc
  changes about that argument — free electrochemistry is the game's first **reversible** minting,
  because a cell's product is chemical potential the combustion rows already know how to cash back
  as heat. Water → cell → burn → water is a closed, mass-conserving loop that nets heat out of
  nothing, and it is buildable **today** with the existing electrolyzer. This plan does not create
  that hole and does not fix it; it does make it cheaper to reach, and `EnergyLedgers.PARKED` is
  still `true`, so nothing in the suite is watching.
- **Power wires and billing**, which moved to `PLAN_power_network.md` rather than being dropped.
  The lever there is the voltage a cell can apply, and that number is already this plan's gate —
  which is the point of making potential a condition rather than a cost. A cell that *sources* energy
  onto those wires is decision 3's deferred battery, and it is the same machine.

⛔ **Retracted (2026-09-06): ion-exchange membranes.** This section previously dismissed them on the
grounds that *"the two electrodes already write into different output stores, which is the same
outcome."* **That was wrong.** Different output stores separate the *products*; they do nothing to
keep the anolyte and catholyte apart, and without that separation the H⁺ and OH⁻ of §5.5 recombine
to water and the acid/base split is worth nothing. The membrane is what makes the separation
persist, which is exactly why the process this arc is modelled on uses them. It is a real component
and it belongs in increment 3.
