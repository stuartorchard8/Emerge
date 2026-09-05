# Economy

Status: **steps 1–5 BUILT and green** (2026-09-01) — money, prices, valuation, the docking port,
stations, docking and the trade UI. **Step 6 (the arc) is all that is left**, and it is a playtest.
⏸ One piece of §7 is deliberately not built: the visual approach — see §10c.
✅ Station size settled at **20×20** (`RigidBody.STATION_TILES`) against the measurement in §10b;
100×100 waits on the coarse box decomposition parked in §6. Numbers in §3
and §3.6 are measured off the live species tables, not invented. Steps in §9.

The milestone is an **early- and mid-game arc**. The end game already exists and is fun — collect
rocks, refine them with a sophisticated setup, build a better vessel — but it opens on a wall of
complexity with no stated purpose, and a new player bounces off it. Money is the missing incentive
gradient: something to want before you understand refining, that pays better the more of the
refining you understand.

> Tier 1 — a vessel that can just about grab an asteroid and crush it. Sell the mixed ore wholesale
> at a station, buy propellant and a little metal, come out ahead. Tier 2 — afford a concentrator,
> sell *pure* species instead of ore, and the same haul is worth several times more. Tier 3 —
> afford a furnace, refine in flight, stop coming home, and start hoarding the tailings because the
> trace metals in them are worth more than everything else put together.

Each tier is paid for by the one before it, and each is a *machine you already have* used earlier
than you would have found a reason to use it.

## 1. What this is not

⛔ **This is not the rigid-body/vessel-grid unification, and must not become it.**
[`PLAN_rigid_bodies.md`](PLAN_rigid_bodies.md) step 6 is two thirds built and the remaining third —
making the vessel a `RigidBody` with `cells`/`shapeAt` — is genuinely open: `collectHullContacts`
rasterises into a tile index and is the **better** traversal of the two, so unifying is not deleting
one. Meanwhile a station needs no interior (Stu, 2026-09-01: "I don't think we need to model the
internals of NPC stations at this stage, or maybe ever").

So a station is a `RigidBody` with a new `BodyKind` and some economic state hung off it. That is
exactly the placeholder `RigidBody.oreComposition` already is for a rock, and it leaves
`PLAN_rigid_bodies.md` free to land on its own schedule. The one piece of physics this milestone
*does* owe is §7's composite body, and that is a weld between two operands, not a unification.

## 2. What already exists, and is more than expected

Almost every abstraction this needs is in the tree already:

- **`Acceptance`** (`world/Demand.kt`) has exactly the three shapes a port needs, and its own doc
  already separates the two questions a sell order asks: `takesAnything` (fussiness) and
  `isUnlimited` (whether the appetite ever ends). A sell order is the `filtered` shape.
- **`SpeciesFilter`** (`world/Material.kt:516`) is `species + minPercent` — a sell order's terms,
  singular. §5 needs it plural; that is the one change to existing demand machinery.
- **`Stockpile`** already answers the two questions the trade UI asks separately: `held` (how much
  is aboard) and `buildable` (what is loose and pure, per species).
- **Buying needs no "buy a machine" concept.** Purchased species land in a storage and the existing
  ghost/build loop spends them. The whole progression is already expressible in what exists.
- **Fuel is already a money sink.** Thrusters vent solid propellant and book `ventedMass`.
- **`bodyImpulseX/Y` and `bodyAngImpulse`** already exist to name momentum crossing between the ship
  and a body, which is precisely what docking is (§7).
- **`Species.relativeAbundance` + `compositionOf`** (`chem/SpeciesInfo.kt:134`) give scarcity and
  elemental mass shares directly. §3's whole price table derives from data already tuned to reality.

Three things genuinely do not exist: **money**, **valuation**, and **a counterparty**.

## 3. Prices

Stu's model, in three layers. All three are measured below against the live tables.

### 3.1 Element base price, from scarcity

`relativeAbundance` is parts per hundred million of a reference rock, by mass, and it lives on the
**mineral** — an element carries one only where it occurs native (iron, nickel, carbon, copper, the
noble metals). So an element's true abundance is what it is worth summed across every rock that
carries it:

```
abundance(e) = Σ over s in Species.NATURAL of  relativeAbundance(s) × massShare(e in s)
```

`massShare` comes from `compositionOf(s)`, and a native element contributes its own abundance at a
share of 1. Measured: **83 elements have a source**, iron comes out at 21,694,420 against its native
7,000,000 — most of the world's iron is in rock, which is the point of the two-tier model.

Price is then inverse abundance, normalised so **pure iron is 1,000 credits per 100 kg**:

```
price(e) = IRON_PRICE × (abundance(Iron) / abundance(e)) ^ γ
```

⚠️ **γ is the knob that decides whether this is playable, and γ = 1 is not.** Measured spread:

| γ | oxygen | iron | titanium | gold | uranium | spread |
|---|---|---|---|---|---|---|
| **1** (literal) | 444 | 1,000 | 434,804 | 765,208,475 | 24,611,317,281 | **7.4 orders** |
| **1/2** | 666 | 1,000 | 20,852 | 874,762 | 4,960,979 | **4.4 orders** |
| 1/3 | 763 | 1,000 | 7,576 | 91,466 | 290,878 | 2.6 orders |

γ = 1 is the *physically honest* one — a gold:iron ratio of 765,000 is roughly the real ratio — and
it is unplayable in a UI shared with a starter ship worth a few thousand credits. γ = 1/3 flattens
the trace metals until hoarding tailings is pointless, which is the mid-game hook.

✅ **SETTLED: γ = 1/2** (Stu, 2026-09-01). Gold stays 875× iron, uranium 4,961×, and the whole table
fits in five digits. Keep γ as one named constant so it can be turned in one place; integer-only via
`isqrt`. Stu's reasoning is worth keeping: γ = 1 is defensible — if uranium is 1/24M as abundant it
*should* be 24M times as valuable — but diminishing returns on rarity is a thing games do on purpose,
and it is what makes the number readable.

### ✅ One abundance table drives BOTH the world and the prices

Stu asked whether the two knobs — raising the abundance of the rare species, and γ — can be tweaked
later, and whether they can be used together. They can, and better than expected:

⚠️ **`RockSpawner` rolls every rock's composition straight off `relativeAbundance`**
(`world/RockSpawner.kt:295`, `ordinary = total × species.relativeAbundance / NATURAL_ABUNDANCE_TOTAL`).
So the same table decides **what the world is made of** *and*, under §3.1, **what things cost**.

⛔ **Do not add a separate price-abundance override.** Keeping one table means raising uranium's
abundance makes uranium more findable *and* automatically cheaper by exactly the right factor — the
two stay consistent for free, and Stu's two-pronged approach (commoner rare metals **and** γ = 1/2)
is one coherent change rather than two that can drift apart. Two tables would let the world say a
thing is common while the price says it is rare, which is the bug this avoids by construction.

⚠️ **Prices are derived, never stored** — a station saves its *stock*, not its price list. So turning
either knob reprices every existing save on load. That is right during development and is worth
knowing before it surprises someone. ⚠️ Rocks already spawned keep the composition they were rolled
with (they live in `bodies` and are saved); a chunk re-rolls with the new table only when it is
re-entered, so an old save ends up with a mix of old and new rock. Minor, and self-healing.

⚠️ **Oxygen is the cheapest element in the game and it is 30–50% by mass of most ores.** That is
correct and it is load-bearing: it is why hauling raw rock pays badly and why the tailings are where
the value hides.
### 3.2 Species price, from elemental composition

```
price(s) = Σ over e in compositionOf(s) of  massShare(e) × price(e)
```

Measured at γ = 1/2, credits per 100 kg: Forsterite **932**, Quartz **876**, Hematite **900**, Water
**969**, Ilmenite **7,164**, Chalcopyrite **12,270**, Uraninite **4,373,090**.

### 3.3 Station-local price, from stock

Stu: "if a station has a lot of iron available, then iron would be purchasable at that station for
prices that asymptote towards 1 credit per 100 kg."

```
localPrice(station, s) = max(FLOOR, price(s) × K / (K + stock(station, s)))
```

`K` is the reference stock at which a station charges half list. `FLOOR` is 1 credit per 100 kg.
One curve, one knob, and it gives "shop around" behaviour for free.

⚠️ **The discount is per SPECIES stock, never per element.** §5.2 stops working the instant anyone
applies it at the element level — see the finding below, which is the single most important sentence
in this document.

### 3.4 ⛔ FINDING: elemental breakdown is exactly value-neutral at base prices

Stu's rule — stations break a species into its elements "if separately they would be more valuable
than they were combined" — **can never fire against §3.2**, because §3.2 *defines* a species' price
as the sum of its elements' prices. Measured, and it is zero to four decimal places, not nearly
zero:

```
Forsterite     whole       931.56   parts       931.56   gain  +0.0000
Hematite       whole       899.83   parts       899.83   gain  +0.0000
Ilmenite       whole     7,163.59   parts     7,163.59   gain  +0.0000
Chalcopyrite   whole    12,270.14   parts    12,270.14   gain  +0.0000
Uraninite      whole 4,373,090.48   parts 4,373,090.48   gain  +0.0000
```

**The station-local discount is what makes it fire**, and it makes it fire for exactly the right
reason: a station glutted with forsterite has a depressed forsterite price while its magnesium,
silicon and oxygen stocks are low and near list. Breaking down is profitable *because the station is
over-supplied in that mineral*, and it self-limits as the element stocks rise.

Simulated on a station holding 200 t of forsterite, K = 10 t, 1 kg/tick:

```
tick    forsterite kg   whole c/kg   parts c/kg      gain
   0          200,000         0.44         9.32     +8.87
5000          195,000         0.45         7.96     +7.50
15000         185,000         0.48         6.19     +5.71
25000         175,000         0.50         5.08     +4.58
```

It converges, but **slowly** — 25,000 ticks is 6.5 minutes at 64 tps and the glut has barely moved.
That is probably right (a station should feel big and slow) but it is a number to look at with your
own eyes before it ships.

### 3.5 ⛔ FINDING: one price curve per station is an arbitrage machine

Buying 100 kg of iron *removes* it from the station's stock, which *raises* the local price, so
selling it straight back pays more than it cost. Free money, at one station, with no travel.

**A bid/ask spread is not optional.** Station buys from the player at `localPrice × (1 − SPREAD)`,
sells at `localPrice × (1 + SPREAD)`, with `SPREAD` large enough to dominate the stock-curve
movement of any trade a player can execute in one docking. A test asserting *a round trip at one
station loses money* belongs in step 1 and is the tripwire for this whole section.

### 3.6 Mixed ore: the purification fee — ✅ SETTLED, share **squared**

Stu: "Mixed ore would be priced based on the top two species present, each at half the going rate.
The rest of the ore content is essentially forfeit as an additional sneaky cost of outsourcing
purification that the player starts ignorant of."

Taken literally — *top two, each at exactly half* — a 100% pure lump is still "the top one species",
so purity would pay half too. It needs to be continuous in purity without losing its shape. Four
candidates, measured against the **real concentrator ladder** (41 → 65 → 86 → 94 → 97 → 100, from
`reference_oos_processor_purity_ladder`) on a 100 kg lump whose non-dominant mass splits 50/30/20
across three other species. All prices equal, so the table is about the *rule* and nothing else.
100 = perfectly separated and sold as pure species.

> ⚠️ **The ladder is gone; the rule is not.** The concentrator draws one species out pure in a single
> pass, so a player no longer walks 41 → 65 → 86 → 94 → 97 → 100 — they see 41% ore and 100%
> concentrate and little in between. Those figures survive here as the *sampling grid* the four
> candidate rules were compared on, which is what this section is deciding. R2 is continuous in
> purity, so nothing about the choice depends on which purities actually occur in play.

| purity | R0 flat-half | R1 share | **R2 share²** | R3 flat fee |
|---|---|---|---|---|
| 41% | 35.2 | 25.5 | **9.5** | 25.5 |
| 65% | 41.2 | 45.3 | **28.0** | 37.5 |
| 86% | 46.5 | 74.4 | **63.6** | 48.0 |
| 94% | 48.5 | 88.5 | **83.1** | 52.0 |
| 97% | 49.2 | 94.1 | **91.3** | 53.5 |
| 100% | 100.0 | 100.0 | **100.0** | 55.0 |
| **41% → 100%** | 2.84× | 3.92× | **10.57×** | 2.16× |

⛔ **Ship R2:**

```
sellValue(lump) = Σ over the top TWO species s of   localBid(s) × mass(s) × (mass(s) / total)²
```

- 100% pure → full rate. Purity pays, with no cliff.
- **10.6× from extractor output to fully concentrated** — the incentive Stu asked for. R1 (the
  share-weighted rule the first draft of this plan proposed) gives only 3.92×, which is what he
  called too low, and he was right.
- The tail beyond the top two is still forfeit, and it is still the rare valuable part. The sneaky
  cost survives, and it is now the *reason* a concentrator pays for itself rather than a flat toll.

✅ **The marginal gains front-load, and that is the right shape**, because the last rungs are already
motivated by something else — `BUILD_PURITY_PERCENT` is 100, so 97 → 100 is what *building* demands
and does not need paying for twice:

```
 41% ->  65%     9.5 ->  28.0   +196.0%
 65% ->  86%    28.0 ->  63.6   +127.3%
 86% ->  94%    63.6 ->  83.1   + 30.5%
 94% ->  97%    83.1 ->  91.3   +  9.9%
 97% -> 100%    91.3 -> 100.0   +  9.6%
```

⚠️ **R2 makes tier 1 deliberately meagre** — raw extractor ore fetches under a tenth of list. That is
the intent (tier 1 should feel like subsistence), but it means §9 step 6's tuning has to make rocks
big and propellant cheap, or the opening is unplayable rather than merely lean.

⛔ R3 (top two at list, minus a flat fee on the whole mass) was rejected: the fee applies to pure
material too, so it caps at 55% of list and pure metal never fetches its price. It also breaks the
case where a 1%-uranium ore is legitimately worth hauling.
## 4. Money

`credits: Long` on `VesselState`. **Deliberately outside the mass and energy ledgers** — it is not a
substance, has no position, and must never appear in `massBalance`.

Save: appended, and gated on **the field's own absence** rather than a version number, following the
`reconciledMass` precedent (`reference_oos_mass_ledger`) — the question is whether this file has ever
had a balance, and absence is the exact answer.

✅ **A new save opens with nothing** (Stu, 2026-09-01). The first haul is the whole stake.

⏸ **PARKED, and worth coming back to: opening in debt.** Stu: many games in this space start the
player as a debt slave, which buys a *time*-based incentive — interest accruing, a mortgage on the
vessel you are flying — that a zero balance cannot. Deliberately not in iteration one, and noted here
so it is not re-derived from scratch. It costs nothing to add later: a negative `credits` and an
interest term, both of which this design already permits.

## 5. The docking port

A 3×3 `DeckMachineKind.DockingPort` — `Storage` is already 3×3 so the footprint machinery is proven
and `FootprintShape.Square` applies. One input port and one output port, per `PortKind`.

### 5.1 The input port sells

Its `Acceptance` is derived from the player's sell list, so **the rail network only ever routes
toward the port what the player has chosen to sell** — which is the whole point of demand-based flow
(`project_oos_demand_flow`: nothing travels toward a place that cannot use it).

⚠️ **A sell list is several species; `SpeciesFilter` is one.** `Acceptance.filtered` takes a single
`SpeciesFilter` today. This needs a plural form — a list, or a species bitmask beside a purity — and
it must stay allocation-free, because `admits` is asked of every candidate direction of every loaded
tile on every step.

Matter arriving is valued by §3.6, credited, and leaves the world.

### 5.2 The output port buys

Purchased species are minted as packets onto the rail network at the output port. ⚠️ **Packets never
merge** (`reference_oos_packets_never_merge`) — the port mints lumps and the network carries them;
it must not try to top up a lump already on the belt.

### 5.3 Ledger terms

⛔ **Four new terms, not two, and not reused ones.**

Mass: `importedMass` and `exportedMass`. **Do not fold these into `extractedMass`/`ventedMass`** —
`extractedMass` is also a term in the *rock* ledger, so closing the vessel balance through it opens
the rock balance silently. That failure mode is already recorded twice
(`reference_oos_mass_ledger`, and `reconciledMass`'s doc).

Energy: `importedEnergy` and `exportedEnergy`. **A `Mixture` carries `energy`**, so a hot lump sold
takes real thermal energy off the vessel, and `heatBalance` will read it as a leak if nobody says so.
This is the same two-ledgers-must-both-be-told shape as `Work.solidBecameGas`, which went unbooked
for the vaporizer's entire life. Book both in one call or it will happen again.

## 6. Stations

`BodyKind.STATION`, plus a `Station` record carrying what a body does not have:

- **`ore: Mixture`** — the mixed reserve bought from players and not yet purified.
- **`stock: LongArray`** per species — the pure piles, which are what §3.3 prices against.
- **A dock node list** — one or more port mouths in body-local coordinates, each with a facing.

Three things a station needs that a rock does not:

1. **Permanence.** `RockSpawner` despawns any body whose chunk leaves an 11×11 window of 64-tile
   chunks (~700 tiles). A station must be exempt, and must be excluded from the spawn roll so
   asteroids do not materialise inside it — Stu's exclusion zone, keyed on the station's origin.
2. ✅ **Discs, and a pose-only dock** (Stu, 2026-09-01: "economy doesn't hinge on the station
   collision box being perfect"). ⚠️ A union of discs cannot make the flat face you would want to
   berth against — the scallop table in `PLAN_rigid_bodies.md` is unambiguous that no radius removes
   it — so docking must not read contact geometry at all. §7's capture is a pose constraint and the
   scallops only ever matter if you *bump* the station.
3. ✅ **A 100×100 station must be a HOLLOW SHELL.** (Stu wants it "pretty large — like 100×100".)

### ⛔ Why a big station is a shell, and why boxes would not have helped

`collectBodyContacts` (`world/Contact.kt:223`) is O(a.cells × b.cells), culled only by a whole-body
bound-radius test. A **solid** 100×100 station is 10,000 cells with a bound radius of ~70 tiles, so
it culls nothing within 70 tiles of itself and every nearby rock costs 10,000 × its own cell count in
pair tests, every substep, every tick. That is not a tuning problem, it is a different order.

⛔ **Per-cell `CellShape.Box` does not fix it**, because the cost is *per cell* whichever shape the
cell is. Boxes change the narrow phase, not the count.

⏸ **PARKED, and the right long-term answer: a COARSE box decomposition.** Stu, clarifying: treat the
whole station as one solid object built from *a dozen* bounding boxes, not from ten thousand cell-
sized ones. That is the standard shape for a large static body and it is roughly **30× cheaper again
than the shell** — twelve colliders against four hundred. It composes with the shell rather than
competing with it.

What it needs that does not exist: **a body whose collider set is decoupled from its cell mask.**
`RigidBody` today is `cells: BooleanArray` plus `shapeAt(cell)`, so every collider *is* a cell. The
narrow-phase primitives are already there — `discVsBox` is exact and closed-form, and box-vs-box has
taken a `bFrame` since step 6 — but `collectBodyContacts`, `collectHullContacts` and `boundRadius`
all walk cells. Done properly it separates **what a body weighs** (cells, unchanged) from **what it
collides as** (a collider list), which is a clean seam and one `PLAN_rigid_bodies.md` would want too.
Scope-cut for this milestone by agreement; revisit when a station has to be more than a berth.

✅ **A shell does fix it, costs nothing, and is what a station actually is.** The perimeter of a
100×100 is ~400 cells against 10,000 — **25× cheaper** — and a station is mostly rooms, so a hollow
`cells` mask is the honest shape rather than a trick. `cellDistribution` (`world/Rotation.kt:215`)
walks the mask and skips empties with no solidity assumption anywhere, so centre of mass, gyration
and `boundRadius` all come out right for a shell **with no code change at all**. Mass follows the
filled cells, which is also correct: a hollow station weighs what its hull weighs.

✅ **This is also what keeps §1's scoping claim true.** The right fix for a genuinely solid body that
large is the *hull's* traversal — `collectHullContacts` rasterises into a tile index instead of going
quadratic over cells, and `PLAN_rigid_bodies.md` records that it is the better of the two and that
giving it to bodies is precisely what step 6 has left. A solid 100×100 station would therefore drag a
slice of the unification into this milestone. **The shell is what avoids that**, and if interior
detail is ever wanted, that traversal is the thing to go and get.

⚠️ **The renderer has a stake in this too.** `MAX_RECTS = 48,000` is *already* exceeded at minimum
zoom in an un-turned view (`project_rotation`); a 10,000-cell station would eat a fifth of the budget
on its own. Another reason the shell is not merely a physics optimisation.

### 6.1 Station industry

Two automatic processes, both capped at **1 kg/tick**, both **one action per tick**, both **go/no-go
at the full 1 kg** — no partial action taken to maximise a margin.

- **Purification.** Draw 1 kg of the dominant species out of `ore` and add it to `stock`. `Mixture`
  already has the exact primitive for this and it is the only safe one: ⛔ `Mixture.take` — **never
  scale a mixture species-by-species** (`reference_oos_microgram_deadlock`).
- **Breakdown.** Walk the composite species in `stock` **most abundant first**; for the first one
  whose elements are worth more separately *at local prices* (§3.4), convert 1 kg into its elements
  and stop for this tick. If the most abundant is not profitable, try the next.

⚠️ At 64 tps, 1 kg/tick is 3.8 t/minute against a `Storage.CAP` of 20 t. Both rates are a dial;
neither has been played with.

## 7. Docking, and the composite body

Stu: docking is allowed only at very close proximity of the two ports; it locks the vessel's pose to
the station; and **the pair forms a combined body with shared collision, centre of mass, momentum
and spin** — a shared rigid-body ledger, not a shared grid.

This is the one piece of real physics the milestone owes. It is a **weld**: the simplest possible
joint, and it can be built by composition rather than inside the solver.

- A `Composite` owns one pose, one momentum, one angular momentum and one `MassDistribution`
  summed over its members; each member's pose is the composite's pose plus a fixed local offset.
- The ship stops integrating its own pose while docked and derives it. ⚠️ This touches `Flight.kt`,
  `driftBodies`, `RockContact`, the camera and every reader of `VesselState.pose`.
- Thrusters fired while docked torque the **composite** about the **composite** CoM. So a player can
  slowly shove a station around, which is physically right and probably delightful.

✅ **The momentum ledger already has the term this needs.** `bodyImpulseX/Y` exists precisely to name
momentum crossing between the ship and a body — "`+J` goes to the body, `−J` to the ship, and the
pair conserves by construction". The capture impulse is that exchange. Book it there and
`momentumBalanceX/Y` and `angularBalance` stay closed; book it nowhere and both read a leak the
instant anyone docks.

⚠️ **Aligning a 3×3 port by hand on a body that may be spinning is hard.** Recommend a soft capture:
inside the proximity and alignment window an affordance appears, and pressing it servos the ship into
the docked pose over ~1 s before the weld forms. A hard physical constraint solved by the contact
solver is a rabbit hole and is not what makes this milestone good.

## 8. UI

- **Inspector panel** for the docking port, on the DECK layer with every other machine setting
  (`project_oos_inspector`): the sell list and the buy list, each species with its current local bid
  and ask.
- **A trade sheet** while docked: what the station holds, what it pays, what it charges.
  ⚠️ Sheets are **modal**, rows are **clipped not wrapped**, and the close affordance is labelled
  `close` (`reference_oos_hud_layout`). Shoot it wide **and** narrow (`-Doos.agent.w=520`).
- ⛔ **A panel is not done until it has been screenshotted** (`project_oos_ambient_chemistry`).
  `agent-scripts/trade.txt` lands with the panel, not after it.
- ⚠️ The bitmap font draws `?` for an em dash — use `·`.

## 9. Steps

Hard shape first (`feedback_hard_shape_first`): the two steps that decide whether the arc has teeth
are 1 and 2, and neither needs a station, a dock or a pixel.

**1. Money and prices, headless.** `credits` on `VesselState`; the element abundance derivation; the
γ curve; species prices; the local stock curve; the bid/ask spread; `sellValue` with §3.6's rule.
*Done when:* purity pays more than the same mass mixed; selling depresses a price and buying raises
it; **a round trip at one station loses money** (§3.5); the whole thing runs in well under a second.

**2. ✅ BUILT — the docking port against a stub counterparty** (`46f28c80`).
`world/machine/DockingPort.kt`, the sell-list acceptance, the buy-order step, the four ledger terms,
`VesselState.dockedMarket` as the stub. 14 tests; 1093 green.
⚠️ **The `heatBalance` half of this criterion could not be met and should not have been written** —
see §10a. `massBalance` is asserted every tick for a thousand ticks and holds.

**3. ✅ BUILT — stations** (`c10fd62f`). `BodyKind.STATION` + `Station` on `RigidBody`,
`RigidBody.stationShell`, despawn exemption, 32-tile clearance, `world/StationIndustry.kt`, save v22
with a `station` line. 14 tests in 0.28 s.
⛔ **The measurement came back badly — see §10b. The station size is now an open question.**

**4. ✅ BUILT — docking** (`5d1ae5a5` placement + geometry, `3ae6099a` the weld, `02c0a45b` the
market). `starterWorld` with one deterministic station, `Station.id` + `DockNode`s, `world/Docking.kt`,
`world/Composite.kt`, `world/Weld.kt`, `Edit.Dock`/`Undock`/`SetDockedThrust`, the dock in the save.
`momentumBalanceX/Y` and `angularBalance` asserted **every tick for 300 ticks** across dock, drift and
undock. 25 tests.

**5. ✅ BUILT — the trade UI** (`1678dffb`). `Sheet.Trade` opening itself on arrival, the docking
port's `BERTH` inspector section carrying the way back plus the interlock and RELEASE CLAMPS,
`Edit.TuneDockingPort`, and `agent-scripts/trade.txt` shooting it wide and narrow.

**6. The arc.** Re-author the starter vessel to the tier-1 ship — extractor, no concentrator (Stu,
2026-09-01) — and tune fuel cost, ore value and machine bills until tier 1 → 2 → 3 paces.
⚠️ §3.6's R2 puts raw extractor ore under a tenth of list, so this step has real work to do: rocks
have to be big and propellant cheap, or the opening is unplayable rather than merely lean.
⛔ This is playtesting, not coding, and Stu is the only oracle for it.

⚠️ Steps 1 and 2 land the milestone's actual content without touching physics at all. Step 3 wants
the box-vs-disc answer (§6, item 2) before it starts.

## 10. Decisions — ✅ ALL SETTLED (Stu, 2026-09-01)

1. ✅ **γ = 1/2**, and the rare species' *abundance* may be raised alongside it — one table, so the
   two stay consistent by construction. §3.1.
2. ✅ **R2, share squared** — 10.6× from extractor output to pure. §3.6. The first draft's R1 gave
   3.92× and Stu was right that it was too weak.
3. ✅ **Discs**, with a pose-only dock. §6 item 2.
4. ✅ **100×100, hollow shell.** Boxes would not have helped; the shell is 25× cheaper and is what a
   station is. §6.
5. ✅ **Start with nothing.** Opening in debt is parked with its reasoning intact — §4.

Nothing blocks step 1.

## 10a. What step 1 turned up

⛔ **Thirteen lanthanides have no source, and it is a chemistry question not an economy one.**
`MINERALS` writes monazite, bastnasite and xenotime with a **single representative lanthanide**
(cerium or yttrium) so their molar masses stay exact; the true site occupancy lives in
`LANTHANIDE_SUITE`, whose own doc calls it "the distribution a refining step should actually use" —
and nothing reads it. So La, Pr, Nd, Sm, Eu, Gd, Tb, Dy, Ho, Er, Tm, Yb and Lu occur in no rock this
derivation can see. They are unobtainable, and `elementPrice`'s floor prices them at the rarity
ceiling (147 M/100 kg), well above uranium. Harmless — no *compound* price moves, because no mineral
contains them — but the dearest entries in the table are things nobody can sell.
⚠️ **Deliberately not fixed here.** Spreading a monazite's rare-earth share across the suite is a
chemistry change with a knock-on to cerium's price, and inventing it inside an economy increment is
exactly the mechanism-to-make-a-problem-go-away that `feedback_no_unrequested_functionality` forbids.
`PricesTest` pins the count at 13 so a future fix surfaces as a failure, not a silent repricing.

⚠️ **The incentive to concentrate depends on how many species the gangue carries**, and §3.6's table
only ever measured one of the two cases. Same purity, different ore:

| 41% iron, gangue is… | worth removing |
|---|---|
| one other species | **~3.7×** |
| three other species (50/30/20) | **~10×** |

With one impurity that impurity is *itself* a top-two species and keeps its share²; with three,
everything past the second is forfeit. Real extractor output is many species, so §3.6's figure is the
right one — but "ore that is dirty in many ways" is what the concentrator is really for, and a test
written against a two-species lump is measuring a different game. Both are pinned in `MarketTest`.

⚠️ **Share-squared does NOT reproduce "top two, each at half".** At 50/50 it pays each a **quarter**,
so a blend fetches a quarter of what the separated piles do rather than a half. That is the extra
bite chosen over R1 and it is correct — but it is worth stating plainly beside the sentence the
feature started from, because the two differ by a factor of two at the exact case Stu quoted.

✅ **The arbitrage guarantee is `cost >= revenue`, strict only when the trade is worth a credit.** A
kilogram of iron at a station holding a hundred tonnes is worth 0.8 credits and both sides truncate
to zero — a wash, which is what the invariant is for. Verified at every size from 1 kg to 99% of the
shelf, in both directions, and it holds **because prices are quoted at the stock a trade leaves
behind** — not because of the spread, which alone fails for large trades.

### What step 2 turned up

✅ **The plural sell filter was free.** §5.1 said `Acceptance.filtered` takes a single
`SpeciesFilter` and that a sell list "needs a plural form — a list, or a species bitmask beside a
purity". It does not: `accepts` is keyed **tile → List\<Acceptance\>** and `Whitelist.room` admits a
lump that *any* demand at the tile wants, so one `Acceptance` per order already unions. The machinery
the locked warehouse needed was the machinery a sell list needs, and no change to `Demand.kt` was
required at all.

⛔ **`heatBalance` is PARKED, and step 2's stated criterion was impossible.** `EnergyLedgers.PARKED`
has been true since step 3 of `PLAN_unit_rescale.md`: the whole-grid energy accumulators are single
`Long`s that overflow above `Kₘ = 1.4e5`, well short of the microgram unit, so **a non-zero
`heatBalance` is expected rather than alarming** and its own doc says not to re-enable the check
piecemeal. Writing "heatBalance stays at zero" into the plan was an error made without reading that.

✅ **What replaced it is better.** The global identity goes through `EnergyLedgers.assertBalanced`,
which asserts nothing today and un-parks in one edit with the rest of the suite. The *booking* — the
thing these four terms exist for — is checked directly and is not parked: one lump, one tick,
`exportedEnergy` equals the lump's energy exactly, and a purchase arrives at exactly
`AMBIENT_KELVIN`. An identity over a whole world was never the sharp instrument here anyway.

✅ **`massBalance` is now one definition**, on `VesselState`. It was written out longhand in the HUD,
the harness and four test files, and the HUD's copy is the one that read the player's own ship as an
8.8 t leak (`reference_oos_mass_ledger`). Adding two terms to a seven-way restatement is exactly how
that happens a second time.

⚠️ **A test trap.** Comparing pure iron against an iron/**nickel** blend measures nickel's price, not
purity — nickel is four times iron at γ = 1/2, so the impure lump was worth more per gram to start
with and the penalty read as 1.55× instead of the ~4× that is really there. **The partner species has
to be priced near the one under test**; forsterite (930 against iron's 1,000) is the right choice.

⏸ **Deliberately deferred to the increment that owns them:** the port is **not** `preventAirflow`
(only the hull and the airlock hold air out, and making a third kind do so changes room topology —
a docking question, not a money one), and `dockedMarket` is **not saved** (nothing owns a market
until stations exist, and persisting a transient one is building the shape step 3 would delete).

## 10b. ⛔ MEASURED: a 100×100 station does not fit the tick budget

The plan asked for this number before committing to a size (§6 item 3). Taken with 24 rocks around
the station and the ship beside it, 200 ticks, medians of five interleaved runs:

| world | 200 ticks | per tick |
|---|---|---|
| rocks only | 100 ms | 0.5 ms |
| + **100×100 hollow, adjacent** | **2,413 ms** | **12.1 ms** |
| + 100×100 solid, adjacent | 17,937 ms | 89.7 ms |
| + 100×100 hollow, **1,000 tiles away** | 120 ms | 0.6 ms |
| + **20×20 hollow, adjacent** | **143 ms** | **0.7 ms** |

✅ **The hollow shell is worth 7.4×**, exactly as §6 argued — and it is **not enough**. 12 ms a tick
against a budget of ~3.5 ms mean / 8.5 ms p95 (`reference_oos_perf_levers`) means one station in view
costs more than the entire rest of the game.

✅ **The far-away row is the diagnosis.** A station nobody is near costs nothing, so none of this is
per-body per-tick work — not `cellDistribution` over a 10,000-cell bounding box, not `boundRadius`,
not the industry step. **It is entirely contacts**, which is precisely the failure mode §6 named: a
station's bound radius culls nothing in its own neighbourhood, so every rock nearby pays
`cells × cells`. It is superlinear in station size because a bigger station both has more cells *and*
sweeps more rocks into its broad phase.

⚠️ **The clearance zone does not save it.** No rock *spawns* within 32 tiles — but rocks drift, and
the player will deliberately haul cargo to a station to sell it. The expensive case is the normal one.

⛔ **Reported, not optimised.** The fix is the coarse box decomposition already parked in §6 — bound
the per-cell walk, or decouple a body's colliders from its cell mask — and inventing one inside an
economy increment is exactly what `feedback_no_unrequested_functionality` forbids. Station size is a
plain argument to `RigidBody.stationShell`, so nothing downstream is blocked either way.

✅ **SETTLED (Stu, 2026-09-01): 20×20 now**, at 0.7 ms/tick, as `RigidBody.STATION_TILES` and the
default for `stationShell`. A hundred tiles is still the station he wants; what unlocks it is the
coarse box decomposition parked in §6, and **nothing but that one constant has to change** when it
lands.

### What step 3 turned up besides

⚠️ **Separating and cracking interact, and a test that forgets it measures both plants at once.** A
station whose dominant ore species is a **compound** puts it on the shelf and the cracker takes it
straight back off — 100 kg separated read as 32 kg on the shelf. The purification test uses **iron**,
an element, which cannot be cracked, so it measures one plant.

⚠️ **Cracking is continuous, not glut-triggered, and §3.4's framing was slightly too strong.** The
gain is exactly zero at *list* prices — but a station is never at list: with any stock at all a
compound is fractionally discounted against its own emptier element shelves, so cracking always pays
a little. At a kilogram a tick that is a slow background drift toward elements which self-limits as
those shelves fill. ✅ **This is good and emergent**: a station is where you go to buy *elements*,
which is exactly what a player building a ship needs. The discriminating test is therefore "a station
already rich in the elements does not crack the compound", not "a station at list prices does not".

⚠️ **The save version bump is a record, not a guard.** The reader has no upper-version check, so an
older build handed a v22 file skips the `station` lines and loads a world silently missing its
trading posts. v22 is what a person diagnosing that would read.

## 10c. What step 4 turned up

✅ **The weld is small because the vessel carries the pair.** The reducer already advances the ship's
pose by its own momentum about its own centre of mass — so handing that *one expression* the pair's
mass, centre and radius of gyration turns it into the pair's advance. No second integrator, no
constraint to solve, nothing to drift. The station's pose is derived from the ship's through an offset
frozen at capture, which is the whole of "rigid".

⛔ **A REAL OVERFLOW BUG in the range test, and taking the difference first was not enough.**
`canDock` did subtract two world coordinates before squaring — the documented §5.3 hazard — and a
station sixty tiles away still gave `dx = 6e10`, whose square is 3.6e21 against a `Long` that stops at
9.2e18. It wrapped, the range test *passed*, and a ship berthed across sixty tiles of empty space.
**The difference has to be bounded before it is squared**; a box reject first holds both squares under
`reach²`. Nothing about the arithmetic looked wrong, and only the test that deliberately shoves the
station out of range found it.

⚠️ **Two fixture lessons from the ledgers.** Momentum handed to a fixture has to say where it came
from, or `momentumBalance` reads the fixture's own drift as a leak for ever — `debugImpulse` is the
store for that. And **the angular ledger has no debug store at all**, on purpose: a fixture that wants
a spinning ship has to give it a *history* instead, which means it took its spin off a body
(`bodyAngImpulse`).

⚠️ **Rigidity is exact to the pose primitives and no further.** The station's world pose is written
out through `toWorld` and read back through `toLocal`, and that round trip is documented at 2.7e-6 of
a tile. Measured here at **five raw units** — 5e-9 of a tile. It does not accumulate: the station's
pose is *derived* every tick, never integrated, so there is no running total for an error to collect
in.

### ⏸ NOT BUILT: bringing the ports together visually

Stu asked for the two hulls to close the last gap so the ports kiss, and was right to be cautious.
Doing it in the **sim** means one of two things, and neither is free:

- **Move the station** to touch the ship — it is the massive object, and watching a trading post slide
  two tiles to meet you reads as the world being wrong rather than as a docking clamp.
- **Move the ship** into the berth — more natural, but the ship's pose is what every body's frame
  conversion is taken against, and rewriting it from inside the edit phase means every later reader in
  that tick sees a pose the tick did not start with.

And either way, **changing the internal geometry of a welded pair changes its inertia**: the joint
centre and the radius of gyration both move, so a pair conserving angular momentum spins up or down
as the gap closes. That is physically correct — it is a skater pulling their arms in — and it is a
strange thing to have happen for a cosmetic two tiles.

⛔ **So it belongs in the view, and the view is step 5.** The gap is at most `Docking.RANGE_TILES`
before capture, the sim can hold the pose it captured at, and the renderer can close the last two
tiles over a few frames without any of the above being true. Left here rather than done badly.

## 10d. What step 5 turned up

⛔ **A SCREENSHOT FOUND A BUG NO TEST WAS LOOKING FOR.** The sheet read `BERTH · STATION 0` for a
station whose id is 1. Both of `StationIndustry`'s plants rebuild a `Station` from two of its four
fields, and the constructor defaults the other two — so **every station lost its identity and all of
its berths one tick after the world started**. Silent, in a value nothing reads until the player
tries to dock, and invisible to every test in `StationTest` because none of them looked at `id` or
`docks` *after* `worked()`. Exactly the shape `RigidBody.copy` warns about in capitals, one field
list over, and the reason "a panel is not done until it has been screenshotted" is a rule.

✅ **The sell side must stay explicit.** Stu weighed an alternative worth recording: no UI at all,
and an **unfiltered demand** on the docking port the moment it berths, with the output side
auto-buying to meet whatever species-filtered demand already exists on the network. It is much
simpler, and he named its failure himself — an unfiltered mouth drains the tanks the player was
saving, *silently*, because a demand is invisible until the belts start moving. The sheet exists so
that nothing is sold which the player has not named.

⏸ **The buy half of that idea is still good and is not built.** "Order whatever the network is
already asking for" is a real convenience with no hazard in it: the demand system knows what every
locked warehouse and construction site wants, and a port could fill those orders without the player
listing species by hand. Worth adding as a *switch* beside the manual list rather than instead of it.

## 10e. The revision — station industry rebuilt (Stu, 2026-09-02)

The arc (step 6) is a playtest, and the first playtest condemned three things at once. All three are
fixed; §6.1 as written above is history.

### ⛔ Every sale went onto the shelves, so the mixed reserve was never written at all

`market.absorbing(forSale)` was unconditional. A 41%-iron lump was scattered across a shelf per
species — **already separated, for nothing** — which handed the player the station's own purification
free and left `Station.ore` permanently empty. A live station ended up quoting well over a hundred
species in sub-gram quantities; its separator ate the seeded 60 t reserve in a quarter of an hour and
was dead machinery for the rest of the game.

✅ A lump of **exactly one species** goes on the shelf; anything else goes to the heap.
⚠️ **Purity is exact, no tolerance.** Two micrograms of forsterite in a tonne of iron sends the lot to
the heap. That is the standard `BUILD_PURITY_PERCENT` already holds the player to, and a tolerance
here is the crumb-swallowing rule the construction path tried and reverted
(`reference_oos_microgram_deadlock`).

### ⛔ A kilogram a tick outran the player

3.8 t/min of free, perfect refining — enough to halve the starter station's own price for its
dominant ore in under three minutes of nobody doing anything.

✅ `OutofspaceReducer.STATION_PERIOD` = **3,840 ticks, once a minute** at 64 tps.
⚠️ **A multiple of the tick rate, not a divisor** — the other way round from every other period, and
the reason this one is stated in minutes. `STATION_OFFSET` = 5, clear of the others mod 8.
✅ `CONCENTRATION_BATCH` = **a tonne**, and it is a **go/no-go threshold, not just a rate**: under a
tonne of the dominant species and the separator does nothing at all. That is what stops a station
picking trace metals out of a dribble of ore — it needs an ore body delivered, and then a tonne a
minute. It still does it automatically, which is a real service; the mark-up on buying the result
back is what keeps doing it yourself the better deal.
⚠️ **The schedule gates the plants, not the counter.** A sale lands on any tick, so the market and the
consignment are installed every tick; only the working is periodic.

### ⛔ Elemental cracking was the wrong game, and three systems said so

The old `brokenDown` split any compound by `compositionOf` mass share: no reagents, no temperature,
no cost, **no direction**.

- **Serpentine gave no water.** Mg₃Si₂O₅(OH)₄ by element is Mg/Si/O/H. Nothing in the game could put
  water back on a station's shelves except selling it some. The `DECOMPOSITIONS` row gives forsterite,
  enstatite and **two waters**.
- **Steel only went backwards.** `Fe₉₉C` is a formula in `MINERALS`, so a splitter could take an alloy
  apart and never make one — and steel is the more useful commodity.
- **Titanium was free.** Ilmenite is one of the commoner minerals in the game; splitting FeTiO₃ by
  element routes straight around the reduction chain `PLAN_ambient_chemistry.md` exists for.

✅ **A station runs `REACTIONS`.** Every row scaled so its **largest product** is `REACTION_BATCH`
(100 kg); available if the shelves hold the whole charge at that size; the most profitable available
row is run, or none. `Reaction.draw` is the new reagent twin of `split` — both apportioned off one
stated total, so a batch closes to the microgram. A station is outside every ledger in the game, so
nothing else would ever catch a drift.

✅ **MEASURED across the whole table: every reaction is exactly value-neutral at list prices** — ±5
credits of rounding on charges worth 900–14,000. A species' price is *defined* as the sum of its
elements' and a reaction conserves atoms. So §3.4's mechanism is unchanged and now covers all of
chemistry: what makes a reaction pay is the **station-local stock discount**, and ⛔ the discount must
stay per SPECIES stock or both sides of the comparison move together and it dies silently.

### The furnace fee

`heatFee` = heat capacity of the actual charge × (onset − ambient) + any enthalpy the row swallows,
at `ENERGY_PER_CREDIT`. The codebase already stated the principle, at `Reaction`'s steel row: *"the
energy a foundry actually spends is spent getting the charge to temperature, which is what
`onsetKelvin` already makes the player pay for."*

⛔ **An exothermic row is never paid back.** A station has no thermal model, no store for recovered
heat and nobody to sell it to. It would also be a *large* mistake: burning carbon releases ~30× what
it costs to light, so a station credited for its own fires would burn every gram it owned for money.
⚠️ **`Reaction.enthalpy(mass)`, never `enthalpyPerKg × mass`** — 3×10⁹ × 10¹¹ overflows `Long` and
comes back *negative*, reading as a large exothermic rebate on an endothermic row. It caught out the
measurement that sized the constant.

✅ `ENERGY_PER_CREDIT` = **a megajoule to the credit**, stated rather than derived (Stu: a fixed cost
per joule, not tied to the availability of anything to burn). ⚠️ Chosen against the game's own tables:
a kilogram of carbon fetches 33.7 credits and releases 32.8 MJ, so carbon prices its own combustion at
1.03 MJ/credit. The measured gradient it buys, as a share of the charge's list value:

| row | onset | fee |
|---|---|---|
| Iron → Hematite | 500 K | 1% |
| Pyrite → Troilite | 1000 K | 3% |
| **Iron + Carbon → Steel** | 1811 K | 6% |
| Firebrick | 1700 K | 12% |
| **Serpentine → …+ water** | 900 K | 16% |
| Fayalite → Iron | 1250 K | 13% |
| Forsterite → Periclase + Si | 1800 K | 34% |
| **Periclase → Magnesium** | 1500 K | 38% |
| Enstatite → Periclase + Si | 1800 K | 50% |
| Quartz → Silicon | 2000 K | 60% |

The carbothermic reductions of the silicates are the expensive end, which is true of the real
process too. ⚠️ **Rutile → Titanium is only 0.8%** and that is fine: the cost of titanium is the
**magnesium**, which occurs nowhere naturally and is itself a 38% row off periclase.

### ⛔ A SCREENSHOT FOUND ANOTHER ONE — a berthed station's work was thrown away every tick

⛔ **While berthed there are two copies of one market**: the shelves on the body, and
`VesselState.dockedMarket`, which is what the trade sheet reads and what the *next* tick installs
back over the body. The sweep worked the station and never put the result in the second copy — so
every batch was overwritten one tick later. The reserve fell, the shelves never rose, and the matter
simply vanished.

⚠️ **Pre-existing, and it means a berthed station's industry has never once had an effect.** Silent
for as long as a station was only watched *undocked*, which is never: a station is only interesting
from a berth.

⚠️ **The schedule test did not see it** — it asserts on the reserve, which is the half stored on the
body and which moved correctly the whole time. What found it was photographing the counter and
noticing the separated forsterite was not on the shelf it had been lifted onto. Third time on this
increment (`STATION 0`, the sub-gram shelves, this): **a panel is not done until it is
screenshotted**, and `agent-scripts/station-industry.txt` is now the shot that would catch it again.

### ⏸ Still open after this revision

- ⛔ **A station has no sink.** Nothing consumes, exports or decays stock, so holdings are monotone up
  apart from player purchases and every shelf drifts toward `FLOOR_PRICE` over a long game. There is
  no equilibrium the economy returns to. Not addressed here.
- ✅ **DONE — the sub-gram shelf dust is migrated away.** Save **v24**, `WORKED_SHELVES_VERSION`: a
  station off a file below it has its **whole shelf tipped back into the heap**, seeded metal and all.
  A shelf below v24 cannot say which of its species got there honestly, and keeping the ones that
  *look* legitimate would be guessing about the player's history — the same reason `purifyFabric` is
  gated on the version and not on what a tile looks like. ⚠️ The cost is that a migrated station has
  nothing to sell until it has separated something, which at a tonne a minute is a while. That is a
  fair description of a business that has just tipped its stockroom into the hopper.
- ⚠️ **A station will run photosynthesis** if it is glutted with algae — `REACTIONS` does not gate that
  row on light, so this is the game's own chemistry rather than a station-specific oddity.

## 11. Hazards carried in from memory

- ⛔ `Mixture.take` is the only exact draw; never scale a mixture species-by-species.
- ⛔ Subsystems fire on **staggered offsets**; a new one gets its own and must not be assumed to run
  on the same tick as anything it reads.
- ⛔ Nothing reads work: decide from `before`, account on the live layer
  (`project_oos_one_tick_causality`).
- ⚠️ A moved expected value gets a question, not a silent edit
  (`feedback_ask_before_chasing_test_numbers`).
- ⚠️ `Map.merge` / `computeIfAbsent` / `System.nanoTime` compile on JVM and fail on JS. Check
  `compileTestKotlinJs`.
- ⚠️ No test over 5 s (`feedback_tests_must_be_fast`). Step 1's whole suite should be milliseconds.
- ⚠️ Don't use `--rerun-tasks` on this repo — it invalidates the multiplatform graph and runs 25+
  minutes without finishing.
- ⚠️ Commit each step as soon as it is green (`feedback_commit_as_you_go`); a sub-second gradle run
  is a cache hit, not a pass.
