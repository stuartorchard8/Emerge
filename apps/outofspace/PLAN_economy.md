# Economy

Status: **nothing built** (2026-09-01). Design settled with Stu in conversation; the numbers in §3
are measured off the live species tables, not invented. Steps in §9, decisions still owed in §10.

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

**Ship γ = 1/2.** Gold stays 875× iron, uranium 4,961×, and the whole table fits in five digits.
Keep γ as one named constant so it can be turned in one place; integer-only via `isqrt`.

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

### 3.6 Mixed ore: the purification fee

Stu: "Mixed ore would be priced based on the top two species present, each at half the going rate.
The rest of the ore content is essentially forfeit as an additional sneaky cost of outsourcing
purification that the player starts ignorant of. This bites extra because the low-percentage stuff
is rarer and generally more valuable by definition."

Taken literally — *top two, each at exactly half* — pure ore is also "the top one species", so a
100% pure lump would pay half rate and purity would be worth nothing. The rule needs to be
continuous in purity without losing its shape. The generalisation that agrees with Stu's stated case
exactly:

```
sellValue(lump) = Σ over the top TWO species s of   localBid(s) × mass(s) × (mass(s) / total)
```

- 50/50 blend → each species at **half** rate. Stu's case, exactly as stated.
- 100% pure → the one species at **full** rate. Purity pays, with no cliff.
- 33/33/33 → top two at a third each, the third species forfeit.

The tail is still forfeit and it is still the rare, valuable part — the sneaky cost survives intact,
and now it is the *reason* a concentrator pays for itself rather than a flat toll.

⚠️ **This is a change to what Stu said, not a restatement of it.** It is in the plan because a flat
half-rate makes a 99%-pure lump pay the same as a 50/50 one, which reads as a bug the first time it
happens. If the cliff is wanted deliberately, say so and it is one line.

## 4. Money

`credits: Long` on `VesselState`. **Deliberately outside the mass and energy ledgers** — it is not a
substance, has no position, and must never appear in `massBalance`.

Save: appended, and gated on **the field's own absence** rather than a version number, following the
`reconciledMass` precedent (`reference_oos_mass_ledger`) — the question is whether this file has ever
had a balance, and absence is the exact answer.

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
2. **A flat face to dock against.** ⚠️ A union of discs cannot represent one; the scallop table in
   `PLAN_rigid_bodies.md` is unambiguous that no radius removes it. Either the station's cells are
   `CellShape.Box` — which needs `collectHullContacts`' ±`reach` grid-space bound widened first, a
   hazard already flagged in that plan — or docking ignores contact geometry entirely and is a pure
   pose constraint. **Start with the pose constraint** and leave the cells as discs; the scallops
   only ever matter if you *bump* the station.
3. **A broadphase that survives it.** ⚠️ `collectBodyContacts` (`world/Contact.kt:223`) is
   O(cells × cells), culled only by a whole-body bound-radius test. A 40×40 station is 1,600 cells
   with a bound radius that culls nothing in its own neighbourhood, so every nearby rock pays 1,600
   tests a tick. **Measure this before choosing a station size** — it may be fine, it may want a
   station-specific spatial index.

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

**2. The docking port against a stub counterparty.** The 3×3 machine, the plural sell filter in
`Demand.kt`, the output port minting packets, and the four ledger terms.
*Done when:* ore routed to the port becomes credits and leaves the world; bought species arrive on
the rail; `massBalance` **and** `heatBalance` both stay at zero across a thousand ticks of trading.

**3. Stations.** `BodyKind.STATION`, the `Station` record, despawn exemption, spawn exclusion zone,
§6.1's two industrial processes, save lines. Save **21 → 22**.
*Done when:* a station survives the player flying 2,000 tiles away and back; a glutted station
visibly works its ore down; the broadphase cost of a station-sized body has been **measured**.

**4. Docking.** Proximity and alignment test, soft capture, the composite body, undock.
*Done when:* `momentumBalanceX/Y` and `angularBalance` are zero across dock → burn → undock; a
docked ship firing a thruster moves the pair about the shared CoM.

**5. Trade UI.** The inspector panel and the trade sheet, with `agent-scripts/trade.txt`.
*Done when:* screenshotted wide and narrow, and the screenshots are in the commit message.

**6. The arc.** Re-author the starter vessel to the tier-1 ship — extractor, no concentrator (Stu,
2026-09-01) — and tune fuel cost, ore value and machine bills until tier 1 → 2 → 3 paces.
⛔ This is playtesting, not coding, and Stu is the only oracle for it.

⚠️ Steps 1 and 2 land the milestone's actual content without touching physics at all. Step 3 wants
the box-vs-disc answer (§6, item 2) before it starts.

## 10. Decisions owed

1. **γ = 1/2?** (§3.1) — or accept γ = 1's seven orders of magnitude.
2. **§3.6's continuous purity rule, or the literal flat half-rate cliff?** This is the one place the
   plan knowingly differs from what Stu said.
3. **Station cells: `Box` now, or discs plus a pose-only dock?** (§6, item 2) Recommendation: discs now.
4. **How big is a station?** Decides whether §6 item 3's broadphase is a problem.
5. **Starting balance** — does a new save open with a stake, or is the first haul the whole stake?

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
