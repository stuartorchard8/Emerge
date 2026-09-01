package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/** Shared temperatures across all systems (air, fabric, vacuum). */
object Temperature {
    /** Deep space, near enough. Everything radiates toward this and nothing gets colder. */
    const val SPACE_KELVIN = 3

    /** Comfortable room temperature — what a freshly built thing starts at. */
    const val AMBIENT_KELVIN = 293
}

/**
 * Constants of a **contact**, rather than of any substance.
 *
 * ⚠️ They lived on `Material`'s companion, which was where they were reachable from and not where
 * they belonged: neither is a property of steel or of firebrick, so neither moved when that enum was
 * deleted — only their address did.
 *
 * ⚠️ Named for the thing it is about rather than for the file it came out of: `Contact` was already
 * taken by the collision type, which is a different sense of the word entirely.
 */
object Joint {

    /**
     * Solid-to-air contact gas-side conductance (film coefficient; prevents instant
     * wall-to-room equalisation).
     *
     * ⚠️ **Unchanged by the move to real densities, and it must stay that way.** This is the
     * *gas* side of the joint — the film of still air against the wall — and the gas was always
     * at real scale: [Stuff.AMBIENT_AIR] is a real kilogram of air in a tile. Only the solids
     * moved. Scaling this with them made every wall equalise with its room inside a tick, which
     * showed up as gas tests failing rather than heat ones.
     *
     * **Derivation**: 20 J/K/tick, stated in [Budget]'s energy units. It is **energy**-dimensioned
     * — millijoules per kelvin per tick — so it moves with `Budget.MILLIJOULE` and not with
     * `Budget.GRAM`. Those two travel together by the `ENERGY_PER_MASS` relation, so writing it
     * this way costs nothing today and keeps it correct if the relation is ever revisited.
     */
    val AIR_FILM: Long = 20L * Budget.JOULE

    /**
     * Exposed-face radiance: mJ/K/tick per face (linear gap, not T⁴; vacuum = excellent
     * insulator).
     *
     * This one *does* scale, because it is the solid side: it sets how fast a hull plate sheds
     * its own heat to space.
     *
     * ⛔ **The reference plate is a calibration anchor, not a claim about hulls.** The figure has
     * to be expressed against *some* plate or it has no units, and it used to be expressed
     * against whatever a hull was assumed to be made of. It is a steel plate at a hull's fill
     * fraction, named as such — the same number as before, and now one that stays put whatever
     * the player builds their ship out of, which is what a calibration constant should do. A
     * titanium hull sheds heat more slowly per kelvin because it holds more of it, and that
     * falls out of the solver rather than out of this constant moving underneath it.
     */
    val RADIANCE: Long get() =
        heatCapacityOf(tileBillOfMaterials(DeckMachineKind.Hull, Species.Steel)) / 6_533L
}

/**
 * The friction of a contact, from the two surfaces that make it: **the smoother one governs.**
 *
 * The same shape of rule as [seriesConductance] and for a related reason — a property of a joint is
 * never better than its worse half. Grip comes from the two surfaces keying into each other, so a
 * rough face against a polished one has nothing to bite on: rock dragged over steel slides at
 * steel's number, not at rock's. It also gives the ordering Stu asked for directly, with metal on
 * metal at the bottom and rock on rock at the top, and it needs no special case at the extremes —
 * a frictionless surface makes every contact it takes part in frictionless.
 *
 * `RollingResistanceSystem` in the engine combines Drockets' two materials the same way, with
 * `min(a.rough, b.rough)`, which is the nearest thing in the codebase to a precedent.
 *
 * ⚠️ Returned in [Flight.FRAC_ONE]ths rather than in per mille, because that is the unit the solver
 * multiplies a normal impulse by. Converted here, once, rather than at the multiply.
 */
fun pairRoughness(a: Long, b: Long): Long = minOf(a, b) * Flight.FRAC_ONE / 1_000L

/**
 * **Above this, a solid conducts because it has free electrons in it** — the line between a metal
 * and everything else, in the units [Species.milliWattsPerMetreKelvin] is stated in.
 *
 * Not a tuned threshold: the table has a factor of four of clear air on either side of it. The
 * poorest conductor the game calls a metal is titanium at 22, and the best it calls a mineral is
 * forsterite at 5. Nothing sits near the line, so no species' answer turns on where exactly it is.
 */
private const val METALLIC_CONDUCTION_MILLIWATTS = 10_000L

/** What a metal's surface grips like — Coulomb's `μ` in parts per thousand. Steel on steel, ~0.45. */
private const val METAL_GRIP = 450L

/** What a mineral's grips like. Rock on rock, ~0.8: a heap of rubble behaves like a heap. */
private const val MINERAL_GRIP = 800L

/**
 * **What a surface grips like, from what it is made of** — and the *only* thing that decides is
 * whether the stuff conducts like a metal.
 *
 * ⛔ **Derived, because nothing in this game is "normally" made of anything.** Grip used to be five
 * numbers hand-written on a [Material], plus a single constant standing in for every rock there
 * could ever be, and the argument for the constant was that per-species grip would be a hundred and
 * seventy invented values. Both problems have the same answer, and it is one the table already
 * holds: metallic bonding is what gives a solid free electrons to carry heat *and* the ductile,
 * smoothly shearing surface that makes it slide. Ceramic and silicate bonding gives it neither. So
 * conduction is not a proxy for grip here — the two are consequences of the same bond, and a species
 * that has never been thought about answers correctly the moment its conductivity is stated.
 *
 * ⚠️ **What is kept is the ordering, which is the only part that was ever real** (Stu's, and the old
 * doc said as much): rubble grips harder than any metal aboard, so a rock stays where it lands on a
 * deck and two rocks grinding together stay put on each other. What is *lost* is the spread among
 * metals — steel 400, iron 450, copper 500 — which was noise: it put the best conductor in the game
 * at the grippiest end of it, which is the opposite of the mechanism above and of the real numbers.
 */
fun roughnessOf(species: Species): Long =
    if (species.milliWattsPerMetreKelvin >= METALLIC_CONDUCTION_MILLIWATTS) METAL_GRIP else MINERAL_GRIP

/**
 * The same question of a blend — an ore body, which is whatever the field made it.
 *
 * ⚠️ Through [conductivityOf], so it is the **mass-weighted harmonic** mean that decides, exactly as
 * it does for heat: a mostly-silicate rock with a little iron in it conducts like the silicate and
 * grips like it too. A trace of metal does not make a rock slippery, which is the right answer and
 * one the arithmetic gives without being told.
 *
 * ⚠️ An empty mixture conducts nothing and so grips like rock. That is the useful reading — a body
 * with no assay is rubble — and it is what the constant this replaced said.
 */
fun roughnessOf(ore: Mixture): Long =
    if (conductivityOf(ore) >= METALLIC_CONDUCTION_MILLIWATTS) METAL_GRIP else MINERAL_GRIP

/**
 * Two things in contact conduct at the **series** combination of their conductances.
 *
 * Heat crossing a joint passes through both sides, so the worse of the two governs — copper bolted to
 * firebrick is a firebrick-limited joint, not a copper-fast one. The harmonic mean says exactly that
 * and needs no special case for the extremes: it is never larger than either input, and it collapses
 * to zero if either side does.
 */
fun seriesConductance(a: Long, b: Long): Long {
    val sum = a + b
    // `2ab/(a+b)` multiplies two conductances together, and a conductance is energy per kelvin per
    // tick — mass-dimensioned, so the product is **quadratic in the mass unit**. A steel hull plate
    // conducts about 1.2e11 at one microgram per unit and the product reaches 2.9e22, which wraps and
    // takes the whole solid-heat solver with it: every contact in the vessel reads a nonsense
    // conductance and nothing conducts anywhere. Taken as the fraction `a/(a+b)` first, the unit
    // cancels out of the ratio and is carried only by the `2b`. Exact wherever the old form did not
    // overflow, and still symmetric in its arguments, since both orderings are exactly `2ab/(a+b)`.
    return if (sum <= 0L) 0L else scaledRatio(a, sum, 2L * b)
}

/**
 * **What one tile of pure [Species] conducts, per kelvin per tick, when it fills a tile completely.**
 *
 * ⛔ **The bridge that lets a building be made of anything.** [Material] is five named substances;
 * this is the same arithmetic asked of any of the hundred and seventy, and it is what a tile's
 * conductance is looked up through now that the tile's material is a fact about the matter in it
 * rather than about its kind.
 *
 * ⚠️ **A table and not a function, for the reason [Material]'s own derived fields are fields**: this
 * is asked per tile per heat tick, and computing it walks a mixture. Species are an enum and their
 * physical constants cannot change, so every evaluation after the first returns the identical number
 * for identical work.
 */
private val SOLID_CONDUCTANCE: LongArray = LongArray(Species.COUNT) { i ->
    val one = Mixture.of(Species.ALL[i] to 1_000L, energy = 0L)
    val centiTicks = conductanceCentiTicksOf(one)
    if (centiTicks <= 0L) 0L else capacityPerTileOf(one) * 100L / centiTicks
}

/**
 * What a tile made of [species] conducts at [fillPermille] — the per-species twin of
 * the deleted `Material` enum's `conductance`, and identical to it for the five it named.
 */
fun conductanceOf(species: Species, fillPermille: Int): Long =
    SOLID_CONDUCTANCE[species.ordinal] * fillPermille / 1_000L

/*
 * ⛔ **`Material.species`, `DeckMachineKind.material` and `Conduit.material` stood here.**
 *
 * The last two were the game's answer to "what is a Storage normally made of" and "what is a rail
 * normally made of" — one `when` apiece, answering for every Storage and every rail that would ever
 * exist. There is no such answer now (Stu): a kind is a *shape and a behaviour*, a conduit is a
 * *shape and what it carries*, and neither of those is a substance. Everything that is built states
 * its own — see [Segment.material] and `DeckArray.materialOf` — and every physical question is asked
 * of the matter actually present.
 *
 * ⚠️ The `Material` enum went with them, and `Material.species` was the seam it was pulled out
 * through. Its five entries were five substances that happened to have names; every number it
 * carried — density, capacity, conductance, grip — has a per-[Species] twin that answers for all
 * hundred and seventy: [massPerTileOf], [capacityPerTileOf], [conductanceOf], [roughnessOf]. What
 * was left of it was a shortlist, and a shortlist of materials is the one thing a game with no
 * default material must not have.
 *
 * Two tables that look like these survive and are not them, and each says so in its own doc:
 * `Save.materialBefore` states what a file written before version 21 *meant*, and
 * `StarterVessel.madeOf` states what one particular ship was built out of. Neither is a rule.
 */

val DeckMachineKind.fillPermille: Int
    get() = when (this) {
        // Plate: a few centimetres of steel over a metre of face, plus framing.
        DeckMachineKind.Hull, DeckMachineKind.Airlock -> 60
        // Mostly a housing, as it was while it was a machine — the number is carried across, not
        // rechosen, so the migration does not quietly change what the ship weighs.
        DeckMachineKind.Vent -> 40
        // A shell with a room's worth of space inside it — carried across unchanged.
        DeckMachineKind.Storage, DeckMachineKind.Pump -> 150
        // A bell, a throat and the plumbing behind them: thicker than an instrument housing and
        // nowhere near a furnace lining, because the hot part of a rocket is deliberately thin.
        DeckMachineKind.Thruster -> 120
        DeckMachineKind.Concentrator, DeckMachineKind.Extractor -> 150
        // A lining thick enough to hold a furnace's heat in, around the space the ore occupies.
        DeckMachineKind.Furnace -> 250
        // Instruments and fittings: mostly a housing. Carried across unchanged.
        DeckMachineKind.Sensor, DeckMachineKind.KeyInput -> 40
        // A gantry: the same fill as the track it carries, because that is what it is — a length of
        // rail held up in the air. Carried across from when a bridge was a fitting, so the migration
        // does not quietly change what the ship weighs.
        DeckMachineKind.Bridge -> 20
        // Fittings on a run, keeping the fill each had while it was a flag on a `Segment` — so the
        // migration does not quietly change what the ship weighs.
        DeckMachineKind.Gauge -> 40
        DeckMachineKind.Valve -> 15
        // A collar and a pair of hoppers: mostly structure, and heavier than a warehouse shell
        // because it is a hole in the ship that has to be strong enough to be one.
        DeckMachineKind.DockingPort -> 120
    }

/** The same fraction for a bare conduit, which is what a fitting-free length of it is. */
val Conduit.fillPermille: Int
    get() = when (this) {
        // Track and pipework, laid across a tile rather than filling it.
        Conduit.Rail -> 20
        Conduit.Pipe -> 15
        // A cable.
        Conduit.Power, Conduit.Signal -> 2
    }

/*
 * ⛔ **`massPerTile`, `capacityPerTile` and `conductance`, per kind and per conduit, are gone too.**
 *
 * Every one of them was `material.<x> * fillPermille / 1000` — a kind's fill fraction applied to the
 * substance it was *assumed* to be — so every one was the deleted answer wearing a different hat.
 * What a tile weighs and how it conducts come from the matter in it, which the deck and track layers
 * have held for some time: `StuffLayer.massAt`, `tileBillOfMaterials(kind, species).total`,
 * `conduitBillOfMaterials(conduit, species).total`, `conductanceOf(species, fillPermille)`.
 *
 * ⚠️ [DeckMachineKind.fillPermille] and [Conduit.fillPermille] stay, and the distinction is the
 * point: *how much of a tile a thing occupies* is genuinely a fact about the kind. A rail is a strip
 * across a tile and a hull plate is most of one, whatever either is made of.
 *
 * `Conduit.ambientPerTile` went with them, unread: `TrackLayers.lay` answers the same question off
 * the metal it actually laid, which is the number the ledger needs, and a bill that apportions is
 * the only thing that can answer it to the unit.
 */

/**
 * **What one tile of a deck machine is made of** — the species, at the masses a tile of it weighs.
 *
 * The per-tile twin of [billOfMaterials], and the thing the deck layer is now *filled with* rather
 * than merely described by. A machine's casing stopped being a lookup on its kind and became real
 * matter sitting in [org.emerge.demo.outofspace.world.StuffLayer], which is what makes it possible
 * for chemistry to act on the casing at all: you cannot decompose a constant.
 *
 * ⚠️ [Mixture.scaledTo] apportions, so this sums to [massPerTile] **exactly** — not to within a
 * rounding. That is load-bearing: the deck's contribution to the vessel's mass used to be this
 * constant and is now the sum of these species, and the two must agree to the unit or the ship
 * changes weight the day the representation changes. Measured identical for every kind.
 *
 * ⛔ **[species] has no default, and must not get one back.** Every bill in the game now takes the
 * material it is a bill *for*, and a default here is a question the caller was never made to ask —
 * which is how a finished steel run came to be weighed against iron and turned into a wall that
 * wanted nothing. A caller always knows: a segment has [Segment.material], a machine has
 * [org.emerge.demo.outofspace.world.machine.DeckArray.materialOf], a brush has the player's choice.
 * Where the answer really is "the kind's own", say `kind.material.species` and mean it.
 */
fun tileBillOfMaterials(kind: DeckMachineKind, species: Species): Mixture =
    tileBills.getOrPut(kind.ordinal * Species.COUNT + species.ordinal) {
        // ⚠️ Scaled to what a tile of THIS material weighs, not what a tile of the kind's default
        // one does. A copper machine and a titanium machine of the same kind are different masses,
        // because they are different amounts of metal occupying the same fraction of a tile.
        Mixture.of(species to 1_000L, energy = Budget.JOULE)
            .scaledTo(species.solidMassPerTile * kind.fillPermille / 1_000L)
    }

/**
 * Interned per (kind, species), for the two reasons [machineBills] is interned: a [Mixture] is a
 * hundred and sixty-five longs and this is asked per ghost per tick, and — the one that is
 * correctness rather than speed — `railAppetites` groups construction sites into classes by **bill
 * identity**, so a fresh instance per call silently puts every tile in a class of its own.
 */
private val tileBills = HashMap<Int, Mixture>()

/**
 * **What a whole machine of [kind] is made of** — its per-tile bill, [tiles] times over.
 *
 * The bill a ghost of it has to reach. Stated here rather than multiplied at the call sites because
 * construction, deconstruction, the readout and the renderer all have to mean the same number, and
 * a machine's cost is a fact about the kind and its footprint rather than about any of them.
 *
 * ⚠️ **[tileBillOfMaterials] multiplied, not [Mixture.scaledTo] of the larger mass.** The two differ
 * by the rounding in the apportioning, and this one has to equal what
 * [org.emerge.demo.outofspace.world.machine.DeckArray.plusAssign] actually lays down — a tile's bill
 * on each tile. Scale the total instead and the target is a few units away from anything the world
 * can hold, so a finished machine reads as forever unfinished, or as finished a gram early.
 *
 * ⛔ **[species] has no default** — see [tileBillOfMaterials], which explains what the default cost.
 */
fun machineBillOfMaterials(
    kind: DeckMachineKind,
    tiles: Int,
    species: Species,
): Mixture {
    val key = (kind.ordinal * (MAX_CACHED_FOOTPRINT + 1) + tiles) * Species.COUNT + species.ordinal
    if (tiles in 1..MAX_CACHED_FOOTPRINT) {
        machineBills[key]?.let { return it }
    }
    val each = tileBillOfMaterials(kind, species)
    val masses = LongArray(Species.COUNT)
    for (s in Species.ALL) masses[s.ordinal] = each[s] * tiles
    val bill = Mixture.of(masses, 0L)
    if (tiles in 1..MAX_CACHED_FOOTPRINT) machineBills[key] = bill
    return bill
}

/**
 * Every machine's bill, worked out once.
 *
 * A [Mixture] is a hundred and sixty-five longs, and the question "is this thing finished" is asked
 * of every machine on every tick — so building the answer fresh each time would allocate a kilobyte
 * per machine per tick for a number that cannot change. Kinds are an enum and footprints are small,
 * so the whole table is a handful of entries.
 */
private const val MAX_CACHED_FOOTPRINT = 32

/**
 * ⚠️ **A map rather than the nested array it used to be**, because the key gained a third dimension
 * when a machine stopped having one material. Kinds x footprints x species is seventy thousand
 * slots, nearly all of which would stay null for ever — a player builds out of a handful of things,
 * not out of a hundred and seventy — so the sparse structure is the honest one here.
 */
private val machineBills = HashMap<Int, Mixture>()

/**
 * **The bill of materials for one tile of bare conduit** — the twin of [tileBillOfMaterials], and
 * the same apportioning, so a run of track weighs exactly [Conduit.massPerTile] a tile however its
 * material is composed.
 *
 * Interned per conduit, for the same two reasons [machineBills] is: the question is asked of every
 * ghost tile on every step and a [Mixture] is a hundred and sixty-five longs, and — less obviously —
 * [railAppetites] groups sites into classes by **bill identity**, so a fresh instance per call would
 * silently put every tile in a class of its own.
 *
 * ⛔ **[species] has no default** — see [tileBillOfMaterials]. This is the one it cost: `railGhosts`
 * asked it without a material and so weighed finished steel track against iron's bill.
 */
fun conduitBillOfMaterials(
    conduit: Conduit,
    species: Species,
): Mixture = conduitBills.getOrPut(conduit.ordinal * Species.COUNT + species.ordinal) {
    Mixture.of(species to 1_000L, energy = Budget.JOULE)
        .scaledTo(species.solidMassPerTile * conduit.fillPermille / 1_000L)
}

private val conduitBills = HashMap<Int, Mixture>()

/**
 * How close to the recipe a delivery has to be, as a percentage — **asked of every species in the
 * bill separately**.
 *
 * A ghost takes a packet whole or refuses it whole, so this is the standard a player has to hit.
 *
 * ### It is 100, and the slack it used to carry was buying nothing
 *
 * At 95 this was the tolerance that let a rail be built out of ordinary iron rather than out of
 * perfectly separated iron, which mattered while perfectly separated iron was **unreachable**: the
 * concentrator's ladder converged on purity asymptotically and a player watched nine stages all
 * render as "99%". `Chemistry.PURE_ENOUGH_PERMILLE` ended that in `fe1d57e8` — a chain now
 * terminates in genuinely 100% packets — so the slack stopped paying for anything and went on
 * charging its price.
 *
 * ⛔ **The price was a machine that could not be moved.** Five per cent of anything was admitted
 * into a casing, including volatiles, and a casing is inert only while it stands: deconstruct it and
 * its matter lands on a rail, where `offGas` is entitled to take the water back out of it. What is
 * left is a pile that no longer sums to the bill it came from, on a site that reads 99% built for
 * ever — [holdsFullBill] counts matter, and the matter is genuinely gone. A microgram of ice went in
 * and a machine that will not rebuild came out.
 *
 * At 100 nothing enters a casing that the recipe does not name, so a casing holds only metal and
 * refractory, and there is nothing in one that off-gassing can take.
 *
 * ⚠️ **The cost is the mirror of the benefit**, and it is real: a lump that is one microgram off its
 * recipe is now construction-inert for ever, where before it would have been swallowed. That is
 * survivable exactly because the concentrator can hit the number — and it would *not* have been
 * before `fe1d57e8`.
 *
 * ### Still per species, though nothing in the game now needs it to be
 *
 * The question is asked of each species the bill names against **its own share** of the recipe,
 * which for a single-species bill — and since steel and firebrick became species, that is every bill
 * — reduces to "the delivery is bill species and nothing else". The general form is kept because it
 * is the [buildableFrom] anti-exploit's real statement, and because material selection would
 * reintroduce multi-species bills; it costs one comparison per bill species.
 *
 * ⛔ **Read as a single aggregate figure it would be wrong even at 100**: "the delivery is 100% bill
 * species" admits pure iron for a bill of iron *and* carbon, and the site then swallows iron for
 * ever waiting on carbon that is already, by that rule, unnecessary.
 */
const val BUILD_PURITY_PERCENT = 100

/*
 * ⛔ **There was a `buildableFrom(conduit, mixture)` overload here and it is deliberately gone.**
 *
 * It built the bill from the conduit's *default* material, so it answered the anti-exploit's own
 * question for a metal the track had not chosen: a steel rail asked whether it could be built from
 * steel got iron's answer. No production code ever called it — the reducer has always had a segment
 * in its hand and a material to state — so it was a loaded gun aimed at whoever wrote the next
 * caller, which is exactly how the finished-steel-run stall happened one layer up. State the bill.
 */

/**
 * Whether [mixture] is something a thing whose bill of materials is [bill] may be built from.
 *
 * ⛔ **This is the anti-exploit, not a convenience.** A ghost is a free length of track until it is
 * paid for, so the one thing that must never happen is material passing *through* one without being
 * usable: let anything in and a player builds a whole network out of slag and the network never
 * costs them a gram of iron. So the question is asked of what wants to *enter the tile*, not of what
 * the ghost would like to keep.
 *
 * What matter has been *made into* is not part of it, and no longer exists to be: powdered iron
 * builds a rail exactly as a bar of it would.
 *
 * Asked of a bill rather than of a conduit or a kind, because a machine's casing is built from
 * exactly the same rule as a length of track and the two must not be able to drift into two
 * opinions about what the purity standard means.
 */
fun buildableFrom(bill: Mixture, mixture: Mixture): Boolean {
    val total = mixture.total
    // Nothing is not a delivery. Answering true would let an empty lump idle on a ghost for ever.
    if (total <= 0L) return false
    val billTotal = bill.total
    if (billTotal <= 0L) return false
    for (s in Species.ALL) {
        val want = bill[s]
        if (want <= 0L) continue
        // What a delivery this size would hold if it were exactly to recipe. Via [scaledRatio]
        // rather than by cross-multiplying: a packet is around 1e11 in these units and a bill is of
        // the same order, so `mass x billTotal x 100` is a long overflow and this is not.
        val perfect = scaledRatio(want, billTotal, total)
        if (mixture[s] * 100L < perfect * BUILD_PURITY_PERCENT) return false
    }
    return true
}

/**
 * Whether [heldMass] is enough matter to have finished the thing [bill] describes.
 *
 * ⛔ **A total, and deliberately not per species.** The composition of a construction site is not
 * this function's business — it is [buildableFrom]'s, at the door, asked of every gram before it is
 * allowed in. Nothing reaches a site's fabric without having passed that test, so by the time it is
 * standing here it is already within [BUILD_PURITY_PERCENT] of the recipe, and asking a second time
 * against a second standard only lets the two disagree.
 *
 * That disagreement was the bug. A per-species finish line is measured in *bill species*; a
 * delivery is measured in *matter*; and the few percent of junk the door lets through is the
 * difference. It accumulates: an extractor built from 97.85% titanium ended up short of its
 * titanium by 391g while standing 100g of *mass* from its bill, so the site asked for four more
 * packets, received four, and finished 291g heavier than its own recipe. Every quantity in the
 * network was correct; the two ends were counting different things.
 *
 * Counted as matter, the sums close exactly: what a site is short of, what a source sends, and what
 * arrives are one number in one unit, and no purity correction appears anywhere between them.
 *
 * ⚠️ **The cost is that a thing can finish slightly off its own recipe** — up to the door's
 * tolerance, and no further. That deal is now free: at [BUILD_PURITY_PERCENT] of 100 the door admits
 * only material that is on the recipe, so "up to the tolerance" is zero and a finished machine is
 * exactly what its bill says. The argument above is left standing because it is the reason these two
 * questions must be asked in the same unit, which does not depend on what the tolerance happens to
 * be.
 */
fun holdsFullBill(bill: Mixture, heldMass: Long): Boolean = heldMass >= bill.total

/**
 * How much of [bill] is present, in parts per thousand — 0 for nothing, 1000 for finished.
 *
 * Total over total, so it reaches 1000 exactly when [holdsFullBill] turns true: the picture and the
 * sim answer the same question the same way, which is the whole reason both live here.
 */
fun builtPermille(bill: Mixture, heldMass: Long): Int {
    val want = bill.total
    if (want <= 0L) return 1000
    if (heldMass >= want) return 1000
    return scaledRatio(heldMass, want, 1000L).toInt()
}

/**
 * A storage's intake filter: one species, and how pure a lump has to be in it to get in.
 *
 * ⛔ **Not a bill, and deliberately not run through [buildableFrom].** A bill states a *recipe* and
 * measures every species in it proportionally; a filter states one species and one threshold, and
 * says nothing at all about what the other 10% is. They coincide only for a single-species bill at
 * exactly [BUILD_PURITY_PERCENT], which is why the temptation to reuse the bill machinery is worth
 * naming and refusing. ⚠️ The two coincide *more* often now that the tolerance is 100 and every bill
 * is one species, which makes the temptation stronger rather than weaker: they still say different
 * things about the other 40% of a 60% filter, and a filter is a player's dial while a bill is the
 * game's rule.
 *
 * ⚠️ **[species] is captured, never derived.** The player locks a warehouse onto whatever it
 * happens to hold, and from then on that is what it holds — a filter that re-read the dominant
 * species each tick would drift with the contents and so would never exclude anything, which is the
 * opposite of what locking means.
 */
data class SpeciesFilter(val species: Species?, val minPercent: Int?) {
    /**
     * Whether [mixture] is pure enough in [species] to be let in.
     *
     * Nothing is not a delivery — the same rule [buildableFrom] opens with, and for the same reason:
     * an empty lump that passes idles at the door for ever.
     */
    fun admits(mixture: Mixture): Boolean {
        val total = mixture.total
        if (total <= 0L) return false
        val dominant = mixture.dominant ?: return false

        val meetsPurityRequirement = minPercent == null || mixture[dominant] * 100L >= total * minPercent
        if (species == null) return meetsPurityRequirement
        return dominant == species && meetsPurityRequirement
    }

    companion object {
        const val MAX_PERCENT = 100

        /** The steps the panel offers, coarse at the bottom and fine where purity starts to cost. */
        val PERCENTS: List<Int?> = listOf(null, 25, 50, 75, 90, 95, MAX_PERCENT)
    }
}
