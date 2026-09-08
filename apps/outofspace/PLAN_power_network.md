# The power network, and the first thing in the game that is not free

Status: **rescoped — unified conduction, conserved charge, solved per tick** (2026-09-08). Increment
0 stands; increments 1a, 1b and 2 come out. Sibling to `PLAN_electrochemistry.md`, which needs a
wire and now has a real one to need — see §9 for where the two interleave.

> Every machine aboard runs on nothing. A furnace's element mints its own joules, an electrolyzer
> mints the energy to break water, and the ledger stays closed because the pool they draw from is
> not a pool anybody tracks. This is that pool.

## 1. ⛔ What was built, and why it is being taken out

Three increments shipped a working single-layer potential network: one `Conduit.Power` layer, a
`PowerCharge` array, Jacobi relaxation, a panel that injects and a cell that draws. It is green, it
runs, and **the model underneath it is wrong in a way no amount of tuning reaches**.

The 2026-09-07 revision made this harder to see rather than easier, because it reconciled the
*document* to the code and in doing so wrote down behaviours the code does not have — a galvanic
discharge branch that can never fire, series wiring that the model cannot express, and the claim
that no ground reference is needed. That revision is superseded entirely. What follows is the
audit that replaced it.

**The load annihilates charge.** `chargeDrawn` is subtracted from a tile and goes nowhere
(`OutofspaceSim.kt:4802`). The panel creates charge from nothing. So behind every device there is an
infinite reservoir at potential zero, and decision 1 of the old plan — *"charge is conserved
exactly, alongside mass"* — is true only inside `PowerFlow.relax`, which is all `PowerFlowTest`
asserts.

⭐ **That is what forecloses the flow model.** Rails move mass, and a flow pass over them means
something *because mass is conserved*. A network whose every node can source and sink without limit
has no flow to solve. This was Stu's objection and it is correct, though not for the reason first
given: the reversible cell would in fact work against an implicit ground — `I = (V − E)/R` is
already one equation with a sign, and Qwen's two-branch pseudocode was simply wrong. What cannot
work is electrons as a conserved quantity you watch move.

**There is no series, ever.** Every device hangs off one shared node, so every device is in parallel
with every other. Two cells cannot be stacked for 2460 mV. A voltage divider between two machines
cannot exist. `PowerFlow.kt:22` claims one falls out; it does not.

**The panel cannot be written down.** A photovoltaic cell is a two-terminal charge pump — electrons
driven from the P side to the N side, building surplus at one terminal and deficit at the other. The
old model has room for one terminal, so the thing the panel *is* has no representation.

**And the arithmetic has a live hole.** `MAX_CHARGE`'s doc still says *"nothing enforces this yet
because nothing yet injects charge"* — stale since increment 1b. Nothing clamps a panel, and there
is no open-circuit stall despite `SolarPanel.kt:18` correctly saying a PV is a current source *"up
to its open-circuit voltage"*. One four-face panel makes 1.2e6 charge a tick against a stated 3e9
bound: some 2500 ticks to breach it, after which `q²/2` overflows a `Long` and the dissipation
apportionment silently stops meaning anything.

⭐ **`chem/Conductivity.kt` and its nine tests are untouched by all of this.** Increment 0 derived
electrical conductivity from thermal by Wiedemann–Franz and it was right; it is now the foundation
the rest of this plan is built on rather than a guard in front of it.

## 2. Decisions taken (Stu, 2026-09-08)

1. **⭐ Charge is conserved globally and nothing is ever minted or annihilated.** Electrons flow;
   there is no ground that is an infinite reservoir. This is what puts electricity on the same
   footing as mass on rails, and it is the decision every other one here serves.
2. **⭐ Conduction is unified: charge rides the same contact graph as heat.** Every layer conducts,
   because conduction is a fact about *matter* and not about which network a fitting belongs to. A
   rail made of copper carries power. So does a hull plate. So does a machine casing. See §3, and
   see `Segment.kt:52` — *"a conduit is a **shape** … and a shape is not a substance"* — which is
   this decision already written down for a different reason.
3. **⛔ Inter-layer flow requires a terminal; intra-layer flow does not.** Each layer is
   intra-connected exactly as heat is. A **terminal** is a conductive rod at one tile that bonds the
   layers present there, and charge ignores an inter-layer join where no terminal stands. This is
   the *single* place the electrical graph differs from the thermal one, and it is what makes wiring
   a choice rather than a consequence of geometry.
4. **Terminals come in two forms.** Built into a machine at stated points — its +ve and -ve — and as
   a standalone **Terminal** machine whose only job is to bond the layers under it. So the player
   decides whether charge travels by wire, by rail, or through the building itself.
5. **⭐ A machine's casing is a parallel path around its own element, so building material becomes a
   power decision.** See §5. This is the sharpest consequence of decision 2 and the first time
   material selection has a functional rather than a structural cost.
6. **Hull-as-ground comes back, as a wiring choice.** The hull is a large, low-resistance,
   *finite* conductor holding real charge. Bond your circuit to it deliberately and it is a return
   path; do not, and it is not. ⚠️ The thing that was wrong was never *"the hull is the return"* —
   it was *"the return is unaccounted."*
7. **⛔ Conductors have zero capacitance and the network is solved per tick.** Not an approximation:
   see §6 for the thirteen orders of magnitude. `SETTLING_TICKS`, `CHARGE_PER_MILLIVOLT` and
   `MAX_CHARGE` are deleted along with the stiffness they were compensating for. Capacitance belongs
   to a **capacitor machine**, which holds it on purpose — which is exactly what the old decision 3
   predicted would come and revisit the fiction.
8. **⛔ The signal layer stays out until power works end to end (Stu).** *"Signal as voltage seems
   like the right choice, but let's leave this alone until we get power working properly end to
   end. No point building more scope before we have the foundations laid."* ⚠️ Note this is a
   deferral, not the old prohibition: signals becoming a real voltage is now the expected direction,
   and §10 records what it would cost.
9. **⛔ The photovoltaic effect is not simulated (Stu).** A panel could be 1×2 with P- and N-type
   silicon at either end and no special implementation at all, and that is *"too much extra physics
   for one machine's functionality."* The panel keeps an internal rule. It is the only machine in
   this plan that gets one, and this is the argument for it.
10. **Billing the existing machines is its own increment, and it is last.** See §9.

## 3. The model

### ⭐ The graph already exists, and it is the heat graph

`stepSolidHeat` builds, every tick, a contact graph over **every solid thing in the world** — hull
plates, machine casings, conduit fittings on all three layers, cargo lumps, buffer stores. Its
bonding rules are:

| | |
|---|---|
| bodies sharing a tile | always joined (`SolidHeat.kt:67`) |
| `DeckStore` casings | joined across faces (`SolidHeat.kt:92`) |
| fittings | joined along their own layer's **drawn links** (`SolidHeat.kt:108`) |

Edge weight is `seriesConductance(k_a, k_b)` — the same harmonic mean `PowerFlow.relax` already uses,
for the same reason: the worse side governs. `Body.conductance` comes off `conductanceOf(species)`,
read from the matter actually in the tile rather than from the machine's kind.

⭐ **Wiedemann–Franz means this is the same graph.** κ/σ = L·T, so the electrical network is the
thermal contact graph with proportional edge weights. `Conductivity.kt` already makes that
conversion. There is no second network to build — there is a second quantity to put on the one
that is walked every tick already.

### The one difference: terminals gate the inter-layer edges

Rule 1 above — *bodies sharing a tile always touch* — is right for heat and wrong for charge. Taken
literally it means a power run shorts to the deck plate it crosses, a rail shorts to the signal wire
it passes under, and every wired machine bonds its circuit to the hull through its own chassis.

So for charge, **rule 1 is gated on a terminal**. A terminal at a tile bonds the layers present
there; absent one, a shared tile carries no charge between layers. Rules 2 and 3 are unchanged.

⭐ **This is better than insulating each machine's terminals from its own casing**, which was the
other way to get circuits to work. A bushing is a structural exception that the player cannot see or
choose. Terminal gating makes the same isolation a *decision*, and it comes with two dividends: a
rail crossing a power run no longer shorts, so crossings are free and **no power bridge is needed**;
and the player chooses their conductor — wire, rail, or the building itself.

### What falls out

**Series and parallel are both real.** A device spans two terminals at two different nodes, so
stacking two cells gives their EMFs in series and a divider is a divider. None of it is written
down.

**A material choice has a performance consequence.** A copper run genuinely beats an iron one by a
factor nobody chose, out of a table nobody had to extend — this was increment 0's promise and the
solve is what finally collects on it.

**Charge is conserved because Kirchhoff is what gets solved.** Not asserted by a ledger bolted on
afterwards; structural.

## 4. Sources: the panel, and one number in `Ambient`

⛔ **There is no sun in the game**, and `Ambient.kt` is emphatic about why: it is *"the only place a
planet exists"*, deliberately without a world map, an altitude or a sphere. So insolation stays **one
scalar on `Ambient`** — how bright it is out there, and nothing else. No sun direction, no shadows,
no day/night, no occlusion by the vessel's own hull. That part of increment 1b was right and
survives, as does exposure counted over a panel's neighbours via `StructureMap.openToSpace`: *the
sun is anywhere outside the vessel* (Stu), and a buried panel has no sky.

### ⛔ The panel becomes 3×3, and here is the constraint that forced it

A one-tile machine's casing is a **single body**, so its +ve and -ve terminals would be the same
node — a dead short that no material choice can fix. `SolarPanel` is one tile today
(`Footprint.kt:20`), and its doc argues for that: *"a plate on the hull, not an installation: one
tile, and you build a bank of them rather than a bigger one."*

That argument loses to the geometry. A panel becomes **three tiles square with its terminals on the
centre line at either end**, which is `Warehouse`'s shape and needs no new footprint machinery. The
3×3 machines were never at risk — `Electrolyzer` and `Furnace` already have opposite tiles to put
terminals on, with casing in between to be the parallel path §5 depends on.

⚠️ **Every existing vessel's panels need re-placing.** Stated here rather than discovered on load.

### The panel's internal rule, which is the one exception in this plan

A panel is a **current source with a stall voltage**: it drives charge from its +ve terminal to its
-ve terminal at a rate set by insolation and exposed faces, and it stops pushing when the potential
difference across it reaches its open-circuit voltage. Two numbers, and the second one is what the
built code is missing — the stall is what bounds the network by construction, which is how the
`MAX_CHARGE` hole in §1 closes without a clamp.

⛔ **This is a special implementation and decision 9 is why.** The emergent alternative is real and
was considered: 1×2, P-type at one end and N-type at the other, photons generating carriers that a
junction field separates, with no rule for a "solar panel" at all. It is the right shape and it is
too much physics for one machine.

⚠️ **This still does not retire the free-energy fiction, it gives it a pipe.** Sunlight is
unlimited, so what the game gains is energy that is **rate-limited rather than costly** — scarcity
without an economy. A fuel-burning generator would be the machine that made energy expensive.

## 5. ⭐ The casing is a parallel path, and that is the whole argument for material

Current entering a machine at its +ve terminal has two ways to reach its -ve: through the element
that does the work, or **around the outside through the casing**. They are in parallel, so the
current splits by conductance.

> ⭐ **A machine built out of copper cannot use power.** Its casing has far lower resistance than its
> internal element, so nearly all the current shorts around the work and dissipates as I²R in the
> chassis. It heats up and does nothing.

So a high-power machine must be built from something **resistive**, in order that the lowest
resistance path from one terminal to the other runs through its own work-doing circuitry. This is
Stu's, and it is the strongest thing in this plan: `PLAN_material_selection.md` deleted the
`Material` enum on the grounds that *nothing is normally made of anything*, and until now the
consequence of choosing has been mass and strength. This makes it functional. A firebrick-cased
furnace works and a copper-cased one is a heater with extra steps.

⚠️ **It is emergent, which means it is invisible.** A player whose electrolyzer does nothing has no
way to discover that its casing is the problem. See §7 — the connectivity readout is not a polish
item, it is what makes this mechanic legible instead of a bug report.

## 6. Capacitance is deleted, and the capacitor is a machine

The self-capacitance of an isolated metre-scale conductor is about **111 pF**. At 1.23 V that is
1.4e-10 coulombs, roughly 1e9 electrons — while one amp is 6.2e18 electrons a second. The entire
stored charge of a tile of wire is **a tenth of a nanosecond of its own current**, and the RC time
constant of a copper run is femtoseconds.

Against a tick, the charge distribution is instantaneous. ⭐ **Zero capacitance is not a
simplification; it is right by thirteen orders of magnitude**, and the quasi-static assumption is
what every real DC circuit analysis makes.

So `SETTLING_TICKS` was never modelling anything. It was a stabiliser for an explicit solver, which
is what `Saturation.kt` warned it would have to be, and it carried a cost the old plan recorded
honestly: a run settles in `L² × SETTLING_TICKS`, so a fifteen-tile trunk takes some 1800 ticks. On
a unified graph that is far worse — a sixty-tile hull is 3600 × 8 ≈ **29,000 ticks** — which is the
arithmetic that closes the question rather than an opinion about it.

### Both, and they are not in tension

- The **resistive network is solved per tick**: Kirchhoff over the graph, seeded from the previous
  tick's potentials, so a steady state converges in near zero sweeps and only *changes* cost
  iterations. What comes out is a current on every edge.
- **Anything with real capacitance is a device that holds state.** A capacitor's charge integrates
  between ticks and enters the solve as a source of `V = Q/C` behind a series resistance — backward
  Euler, unconditionally stable, no dial.

⭐ **The old decision 3 predicted this exactly.** It named the capacitor machine as *"the thing that
would revisit"* the wire's fictional capacitance. Solving per tick is that revisit: the fiction is
not retuned, it is deleted, and the machine is what is left standing. A supercapacitor at ~1 F holds
1e19 electrons at 1.23 V — ten orders of magnitude past a wire — so the two were never the same kind
of object.

⚠️ **What is given up.** Charge stops being stuff you can watch sitting on a tile, the way matter
sits on a rail. What replaces it is a current per edge, which is what a rail gauge already shows and
what a flow pass is for.

## 7. ⚠️ What must be true, and what will hurt

**The connectivity readout is mandatory, not polish.** A short is emergent, silent and fatal, and
three separate mechanics here produce one: a casing of the wrong metal (§5), a terminal placed where
two circuits meet, and — worst — **two machines that are simply adjacent**, because `DeckStore`
casings face-bond whether or not anybody wired them (`SolidHeat.kt:92`). Circuits will join through
*placement*. The inspector must answer *"what is this galvanically connected to?"* and it must do so
before the first player-visible circuit exists. ⚠️ The bitmap font draws `?` for an em dash — use `·`.

**Air leaves the walk.** It is a node in the thermal graph and must not be one for charge, until
somebody wants arcing.

**The solve needs a reference and it is a gauge choice.** A purely resistive network is singular:
potentials are determined only up to a constant per connected component. Pin one node per component.
Nothing physical rests on which — every device reads a *difference across itself*, never an absolute
potential, and that is the invariant to write a test against.

⛔ **The knee stops being an absolute.** Water's 1230 mV is a potential difference across the cell's
own two terminals, not a reading against a vessel-wide zero. This is the single largest behavioural
change in the rewrite and it is what makes the cell reversible by sign rather than by branch.

**Determinism is the main implementation risk.** An iterative solve with a convergence criterion, in
a codebase that is fixed-point throughout, is where non-determinism gets in quietly. Either a fixed
sweep count or an integer tolerance — never a float epsilon. ⚠️ `Frac` holds about [-2, 2], so
potentials need their own scale and must not borrow it.

**Perf is a measurement, not a guess.** A second solve over the contact graph that `stepSolidHeat`
already builds, seeded so the steady state is cheap. Per `reference_oos_perf_levers`, measure the
phase *share* interleaved — machine timings drift ±25% — and do it before deciding anything.

**Migration is total.** Save version bumps, `PowerCharge` leaves the format, every vessel's panels
move and every vessel's power stops working until it is rewired. The precedent is the old increment
3's framing, which accepted exactly this for billing.

**`Conduit.Power` may not survive.** If any metal conducts, a power layer is just a thin cheap
run you laid to conduct, and `Conduit.Pipe` was deleted for less. Left open deliberately: it may
still earn its place as a *shape* — high conductance per gram of build cost — and that is an
increment-3 question, not one to settle here.

## 8. Resistive heating, which nobody has to write

`I²R` on each edge, banked into the solid heat ledger the furnace element already writes into. So a
run of undersized wire warms up, a copper-cased machine cooks itself (§5), and a panel wired to
nothing sits at its stall voltage doing nothing at all. ⭐ None of these is a rule; they are all the
same rule.

⛔ **Apportion the real drop, do not sum the per-edge figures.** This was increment 1a's finding and
it survives the rewrite intact, because it is a fact about simultaneous solves rather than about the
old model: per-edge dissipation summed came to **5.2% less** than the field actually lost, since a
node shedding to several neighbours at once has cross terms no per-edge formula sees. Keep the
per-edge numbers as *weights* and apportion the true total across them — `apportion` telescopes, so
the shares sum back exactly. ⚠️ It would have passed any tolerance-based test. It was caught because
the ledger was written as an **identity**, and the new ledgers must be written the same way.

⚠️ **`EnergyLedgers.PARKED` is still `true`.** Conserved charge plus a closed energy path is the
first time unparking it is even meaningful: light in, chemical energy stored, heat out.

## 9. Increments

**Each increment is one commit on `main`**, green before it lands.

⚠️ This plan interleaves with `PLAN_electrochemistry.md`, whose increments 0–1 are done and produced
the threshold load this network is designed against. Its increment 2+ (copper, the leach, the loop)
follows increment 4 below.

### Increment 0 — the guard ✅ BUILT (2026-09-06), and it stands

`chem/Conductivity.kt` and `ConductivityTest`, 9 tests. Conductivity derived from the thermal column
by Wiedemann–Franz, scored against measured σ: **the ten metals a wire would be drawn from land
within 15%**, tin exact to three figures. The poor metals — manganese, bismuth, tungsten — reach 60%
and are checked at a looser bound rather than excluded, because a manganese wire should still be bad
by roughly the right amount.

#### ⛔ What the build found: the metal line has no clear air

`Material.kt`'s `METALLIC_CONDUCTION_MILLIWATTS` calls a solid a metal above 10 W/m/K and claims
*"the table has a factor of four of clear air on either side of it."* **Fourteen species now sit in
that gap, and it misclassifies in both directions:**

| Above the line, and a mineral | Below the line, and a metal |
|---|---|
| pyrite 20, cassiterite 12, hematite 11.3, thorianite 10 | mercury 8.3, manganese 8, bismuth 8 |

Deriving conductivity from that threshold would let a vessel **draw wire out of iron ore**. So
`Conductivity.kt` states what a metal *is* — an element that is not one of twenty non-metals, plus
one alloy — which is a fact about chemistry rather than a threshold that drifts as the table grows.

⚠️ **This is still a live defect in `roughnessOf`.** That function reads the same threshold, so today
hematite and pyrite grip like metals and mercury grips like rock. See §10.

### Increment 1 — the graph: what is bonded to what

The electrical contact graph as a sibling of the thermal one, and **nothing on it yet**. Same bodies,
same intra-layer rules, inter-layer edges gated on terminals, air and insulators excluded. Terminals
exist as *data* — a machine declares which of its tiles are terminals — with no machine and no brush
for them yet.

Testable without a solve, and it is the question the readout will ask too: a rail crossing a power
run is **two** components; a terminal at the crossing makes it one; a copper-cased machine's two
terminals are one component and a firebrick-cased machine's are two.

⚠️ **Split out deliberately.** The old increment 1a was split off on the argument that a network
with no source is *"entirely testable by injecting charge directly, and the relaxation is the part
carrying the design risk."* Same reasoning one level down: connectivity is the part carrying the
design risk now, and it is testable with no electricity at all.

### Increment 2 — the solve

Integer Kirchhoff over the graph, seeded from the previous tick, with a reference pinned per
component. Sources enter as EMF edges. `I²R` banked through the existing `heat()` path, apportioned
per §8.

Two ledgers as **identities**: current sums to zero at every node to the unit, and every joule the
network gives up becomes heat. Plus the tests that finally prove the claims the old doc only made —
series resistors, parallel resistors, a divider, and a long thin run of the wrong metal failing to
deliver what a short fat one can.

### Increment 3 — the panel, the terminal, and seeing what you built

`SolarPanel` at 3×3 with terminals on the centre line, as a current source with a stall voltage. The
standalone **Terminal** machine, its brush, and the cut tool. And the connectivity readout, which
lands here because this is the first commit in which a player can build a short.

⛔ **The readout is an OVERLAY, not an inspector line (Stu, 2026-09-08)** — the shape `Overlay.kt`
already has: `None/Heat/Air/Pressure/Density/Flow`, cycled on `H`, with HUD buttons for direct
picks. A short is a fact about a *region*, not about a tile, so a per-tile readout answers the wrong
question — the player needs to see two things they believed were separate wearing one colour.

⚠️ **Colour by component, not by potential.** Potential is a scalar the inspector can print for one
tile; which circuit a tile belongs to is the thing nothing else can answer, and it is what diagnoses
a short. A second potential overlay is cheap to add later if the first one leaves a question.

⚠️ A new overlay needs a cadence — `OutofspaceRenderer.kt:1386` maps each one to the pass that feeds
it, and per `project_oos_interpolation_cadence` the pass stamps when it ran and the view never
infers a schedule.

⚠️ **The readout is not deferrable to increment 4.** `Conduit.Power` was once kept out of the build
menu on the grounds that *"a brush for it would lay cable that does nothing and looks like a bug
rather than like a feature that has not arrived"* — the same judgement applies to shipping shorts
the player cannot see. ⛔ **A panel is not done until screenshotted.**

### Increment 4 — the cell, forward and reverse

`I = (ΔV − E) / R_internal`, where `E` is the reaction potential across the cell's own terminals and
`R_internal` is its electrolyte. **One equation; the sign decides.** Above the knee it splits water
into the hydrogen and oxygen stores; below it, it burns them back and drives the bus.

⭐ **That is a regenerative fuel cell, and it is how spacecraft actually do this.** It is the
battery, with no battery machine: panels charge the bus by day, the cell banks it as chemistry, and
the cell holds the ship up when the panels go dark. The old plan listed a battery under *"explicitly
not doing"* and then under *"it is §2 of the model"*; it is neither. It is this increment.

### ⚠️ The ports become a T, and a terminal shares a tile with a rail

Pointing up: **feed at the bottom of the stem, terminal A + output A middle-left, terminal B +
output B middle-right** (Stu, 2026-09-08). Today's offsets are `Input (-1,0)`, `Product (+1,0)`,
`Waste (0,+1)` (`BufferRole.kt:176`), so this is a three-way rotation of a table that already exists
— and `BufferRoleTest` holds `localBufferOffset` and `portsOf` in agreement, so both move together.

⭐ **The arms are what §5 needs.** Two terminals on opposite tiles with the machine's casing between
them is exactly the parallel path: a copper-cased cell shorts around its own electrolyte and does
nothing but warm up. The old layout put *input* and *product* on the opposite pair, which is the
wrong pair to hang terminals from.

⛔ **A terminal sharing a tile with an output port makes that output's rail a conductor**, because a
terminal bonds the layers present at its tile (§3) and a rail is one of them. So the hydrogen belt
sits at the cathode's potential and the oxygen belt at the anode's — and if both belts belong to one
connected rail network **made of metal**, the cell is shorted through its own logistics and stops.

⭐ **And the answer is a material, not a rule (Stu, 2026-09-08).** A rail conducts only if it is made
of something that conducts, and `Segment.material` is per **tile**. So:

> ⭐ **One insulating segment in a metal run is a galvanic isolator made of track.** Packets cross it;
> charge does not.

Nothing has to be built for this. `electricalConductivityOf` already returns `0L` for a non-metal
(`Conductivity.kt:101`), `seriesConductance(0, b)` is zero, and the walk already skips a segment that
conducts nothing. `Stockpile.buildableSpecies` puts no structural constraint on what a run is drawn
from, and creative mode's standing allowance is *"a structural metal, a conductor, and a rock"* —
**Forsterite is already in the list**, described in its own doc as *"one that is not a metal at
all."* ⭐ That comment was written to say the three choices should let a player *"feel the difference
between the choices"*, at a time when the difference was mass and strength. This is what it turns
into.

⚠️ **So the isolation is a build decision with a visible cost**: an insulating segment is a segment
that is not metal, and a run's material is already a strength and mass decision. The player trades
one against the other.

⭐ **It is also the right answer twice over.** The two gases already must not meet, because
`2 H₂ + O₂ → H₂O` lights at 773 K and a store reacts with itself — the whole reason `Electrolyzer`
has a second output port at all. Now the two *rails* must not conduct to each other either, for an
entirely independent electrical reason, and a real plant separates them for both. ⚠️ But a player who
runs both metal belts into one network kills their cell and has no way to see why, which is what the
increment-3 overlay is for.

⚠️ **Whether the bath is the input store or a fourth `Inside` store is open.** A 3×3 has nine tiles
and `BufferRole` costs one apiece, so both fit; `PLAN_electrochemistry.md` §5.5 wants a standing
electrolyte and the feed is a throughput, which argues they are different stores. Stu's call.

⭐ **The electrolyte ceiling lands here too.** `chem/Cell.kt` has `electrolyteStrength` and pure
water scores zero — seven orders of magnitude below brine — so **a cell full of pure water fails
because the network cannot push current through it**, not because anything forbids it. That needs
the standing bath `PLAN_electrochemistry.md` §5.5 adds, and it is the load model's `R_internal`
rather than a gate bolted on beside it.

⚠️ **Keep increment 2's chatter finding.** A cell allowed to spend its whole tile drained itself
below its knee and limit-cycled on five ticks in forty *with power to spare*. The answer was neither
hysteresis nor a dial: **the load was wrong**, because a cell's current is driven by its overvoltage
and falls to zero as the bus approaches the potential the reaction needs. `I = (ΔV − E)/R` has that
property built in. The old decision to defer hysteresis until something was measured chattering
stands, still unused and now vindicated twice.

### Increment 5 — billing what is already free

Separate and last, for two reasons: a bug in the network would otherwise read as a balance problem
and vice versa, and the day this lands every vessel aboard stops working.

⭐ **The migration is one this codebase has already done.** `Wiring.kt` added `SignalSource.Always`
precisely so *"placing a machine still just works and wiring remains something you add"*. Same move:
a machine with no terminals draws nothing and runs free, terminals are opt-in, and the day the
default flips is its own commit with its own argument.

The furnace goes first. `HEATER_POWER` is joules-per-tick derived from a physical climb rate, so it
is the one machine whose bill is a real number rather than a figure invented for the occasion. ⚠️
`Electrolyzer.MASS_PER_TICK` is knowingly overpowered — an implied 1.3 GJ a tick, some 3700 furnace
elements — and this is the increment where that arithmetic gets settled rather than noted.

## 10. Explicitly not doing

- **The photovoltaic effect.** Decision 9, and §4 records the emergent version that was declined and
  why. The panel is the only machine in this plan with an internal rule.
- ⛔ **The signal layer.** Decision 8 — deferred until power works end to end, and expected to be
  right eventually. ⚠️ What it will cost when it comes: `SignalNetworks` is a **connected-component**
  model with one value per component and no gradient (`SignalField.kt:22`), so signals are instant
  today. Charge on this graph is not. Unifying them makes a button take ticks to reach a door across
  the ship, and reverses `Wiring.kt`'s deliberate deletion of the proportional controller. That is
  its own plan and its own argument.
- **AC, phase, inductance, or anything that is not a resistive DC network.** None of it buys a
  behaviour a player would notice on a vessel this size.
- **Sun direction, shadows, day/night, or a panel that cares which way it faces.** §4. One scalar.
- **Transmission loss as a separate mechanism.** It is `I²R` and it is already there.
- **Arcing across a gap**, which is what letting air into the charge walk would mean. §7.
- ⛔ **Fixing `roughnessOf`'s metal test.** Increment 0 found `METALLIC_CONDUCTION_MILLIWATTS`
  misclassifies fourteen species, so hematite and pyrite currently grip like metals and mercury like
  rock. `conductsElectrically` is the correct predicate and the fix is to route grip through it —
  but that re-tunes every friction interaction in the game, which is Stu's call and belongs in a
  commit whose subject is collision rather than power.
