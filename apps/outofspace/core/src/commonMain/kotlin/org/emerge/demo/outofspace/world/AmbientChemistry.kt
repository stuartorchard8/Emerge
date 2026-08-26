package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.COMBUSTIONS
import org.emerge.demo.outofspace.chem.COMBUSTION_COUNT
import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
import org.emerge.demo.outofspace.chem.LOWEST_COMBUSTION_ONSET
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.LOWEST_DECOMPOSITION_ONSET
import org.emerge.demo.outofspace.chem.LOWEST_REDUCTION_ONSET
import org.emerge.demo.outofspace.chem.OXIDATIONS
import org.emerge.demo.outofspace.chem.Oxidation
import org.emerge.demo.outofspace.chem.REDUCTION_GROUPS
import org.emerge.demo.outofspace.chem.WIDEST_REDUCTION_GROUP
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportionInto
import org.emerge.demo.outofspace.chem.fluid
import org.emerge.demo.outofspace.chem.massAtReducedDensity
import org.emerge.demo.outofspace.chem.reducedTemperature
import org.emerge.demo.outofspace.chem.saturatedVapourDensity
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What a pass of ambient chemistry moved **between the solids and the air**, in both directions.
 *
 * Four numbers, not two, and never netted. Increment 1 only ever ran one way — carbon leaving a
 * belt to become CO₂ — so one mass and one energy said everything. Iron oxidising runs the other
 * way: the oxygen leaves the atmosphere and stays in the solid as scale. Summing the two into a net
 * figure would close both identities for the wrong reason, and the first reaction whose two
 * directions happened to be equal would look like no chemistry at all.
 *
 * [toGasMass]/[toGasEnergy] are what `solidBecameGas` books; [toSolidMass]/[toSolidEnergy] are what
 * `gasBecameSolid` books. Each pair travels together for the reason that function exists: the solid
 * ledger and the air ledger are separate identities, and telling one without the other reads as two
 * unrelated leaks in opposite directions.
 *
 * ⚠️ **One pass fills each pair and neither fills both.** [oxidise] can only ever move matter *into*
 * the layer, so its gas pair is always zero; [offGas] can only ever move matter out of it, so its
 * solid pair is. They share this class because they close the same two identities and the caller
 * adds them up, not because either is capable of the other's direction.
 */
class ChemistryStep(
    val toGasMass: Long,
    val toGasEnergy: Long,
    val toSolidMass: Long,
    val toSolidEnergy: Long,
    /**
     * Energy the reactions **made**, net — negative when they took more than they gave.
     *
     * A fifth number and a different kind from the other four: those are mass changing medium and
     * the heat that rode along with it, which is energy *moving*. This is energy appearing out of
     * chemical bonds or disappearing into them, so it belongs to neither medium's ledger and needs
     * telling on its own.
     *
     * ⚠️ **Positive is energy released.** Burning is strongly positive — a fire is a source — and
     * calcining is strongly negative, which is why a decomposer's element has to keep working.
     * This sentence had the two signs the wrong way round until 2026-08-26; the arithmetic never
     * did, and `reactionEnergy` adds this straight into `generatedEnergy`, which is a source term.
     */
    val releasedEnergy: Long,
) {
    val isNothing: Boolean
        get() = toGasMass == 0L && toGasEnergy == 0L && toSolidMass == 0L &&
            toSolidEnergy == 0L && releasedEnergy == 0L

    companion object {
        val NOTHING = ChemistryStep(0L, 0L, 0L, 0L, 0L)
    }
}

/**
 * One pass of every [Oxidation] over everything [layer] holds — increments 1 and 2 of
 * `PLAN_ambient_chemistry.md`.
 *
 * Chemistry as a property of matter and conditions rather than of a machine: nothing here asks what
 * kind of thing the carbon is sitting on. A lump on a belt burns for the same reason and by the same
 * arithmetic as a lump anywhere else would, which is the whole point of putting every layer's matter
 * in the same store.
 *
 * ### The three things a tile is asked
 *
 * Occupancy is free — [StuffLayer.forEachOccupiedTile] walks rows, so an empty vessel does no work
 * at all. Then: is the matter hot enough for *anything* (one compare against [LOWEST_ONSET], which
 * is where nearly every tile stops), is there oxygen in the air above it (one lookup in a [Fluid]-
 * wide array since increment 0), and is a reactant here (one bitmask-driven lookup per reaction, not
 * 165). Only a tile that answers yes costs anything more.
 *
 * ### One tile's oxygen, two reactions wanting it — the Jacobi rule
 *
 * Every reaction is asked what it **wants** against the same snapshot, before any oxygen has been
 * taken; only then is the tile's supply handed out, by [apportionInto] if it is oversubscribed and
 * in full if it is not. Nobody's answer depends on when it was asked.
 *
 * ⛔ **Never let iteration order decide this.** Reacting each in turn against a dwindling supply
 * would give the whole tile to whichever entry of [OXIDATIONS] came first — a rule no player can
 * predict, and the same leftward bias `stepSolidHeat` and the rigid-body solver are both required
 * to avoid.
 *
 * ⚠️ **"The oxygen attacks the carbon first" is an outcome here, not a rule.** Carbon's base rate is
 * the larger, so at a shared temperature it asks for the larger share and gets it. There is no
 * priority list, which is what lets the plan's no-atmosphere room be a strategy the physics produces
 * rather than a special case somebody wrote.
 *
 * ### ⛔ A reaction may not put gas anywhere. Its products stay in the layer.
 *
 * Every product joins [layer] as its own species, whatever phase that species would be standing on
 * its own: a calcining rock keeps its CO2, a burning lump keeps the CO2 it just made. Releasing it
 * is [offGas]'s job, and [offGas] asks the two questions this function is in no position to answer —
 * **is there anywhere for it to go, and do the conditions there want it as a gas.**
 *
 * That is not a guard bolted on; it is the removal of the only path. This used to vent a gaseous
 * product straight into `air` at whatever tile the matter was sitting on, and it took no
 * [StructureMap], so a lump reacting inside a bulkhead put its gas **inside the hull plate** — where
 * every face reads `CLOSED` and `diffuseFluid` can never reach it again. 18.45 kg of a live save was
 * sealed in six plates that way. See `SealedTileGasTest`.
 *
 * ⚠️ **Chemistry still runs in there, and must.** The whole argument of this file is that a reaction
 * is a property of matter and conditions and never asks what the matter is standing on. Refusing to
 * react inside a wall would be the cheap fix and a different game — ore would stop refining the
 * moment it crossed a doorway. What changed is where the products go, not whether they are made.
 *
 * ### What crosses, and what has to be told
 *
 * The oxygen iron takes **leaves the air ledger and joins the cargo ledger**, and after the above
 * that is the only crossing left here. Those are two different identities — `inTransit + vented +
 * built == extracted + baselineCargo` on one side, `atmosphere + airVented == injected +
 * baselineAir` on the other — and neither knows about the other, so the caller has to book it. See
 * [ChemistryStep].
 *
 * The heat goes with the matter: a share of the air's thermal energy proportional to the mass
 * arriving, the same construction [handOver] uses between a room and a pipe. It is not optional — a
 * lump that took in warm oxygen and gained no joules would have lost them where no gauge could see.
 *
 * ⚠️ **That share is taken against a snapshot of the tile's air**, read before any reaction has
 * touched it. Each reaction taking its share of what the previous one left would be arithmetically
 * fine and order-dependent, which is the one thing the demand pass above exists to prevent.
 *
 * ⚠️ **Give it the layer whose contents are cargo.** Rail and buffer contents are counted by
 * `cargoMass`; the deck's matter and the conduits' own metal are *fabric*, counted by `builtMass`,
 * and burning those closes a different identity that nothing here writes. That is why this takes a
 * layer rather than sweeping all of them: which ledger the matter belongs to is the caller's
 * knowledge, not this function's.
 */
fun oxidise(layer: StuffLayer, air: MassArray, airEnergy: EnergyArray?): ChemistryStep {
    var toSolidMass = 0L
    var toSolidEnergy = 0L
    var released = 0L

    // Allocated once for the whole sweep, not once per tile. A few longs is nothing; a few longs at
    // every occupied tile of every layer every pass is a shape of cost that only ever shows up as
    // "the chemistry is slow".
    val demands = LongArray(OXIDATIONS.size)
    val allowed = LongArray(OXIDATIONS.size)
    // Sized by the widest group rather than per group, so one pair of arrays serves the whole
    // reduction table — see [WIDEST_REDUCTION_GROUP] for why they are hoisted at all.
    val reductantDemands = LongArray(WIDEST_REDUCTION_GROUP)
    val reductantAllowed = LongArray(WIDEST_REDUCTION_GROUP)

    layer.forEachOccupiedTile { tile ->
        val kelvin = layer.kelvinAt(tile)
        // Where nearly every tile in the game stops, for one compare.
        if (kelvin < LOWEST_ONSET) return@forEachOccupiedTile

        // The tile's **air** as it was before anything reacted. Every energy share below is a
        // share of these, so no reaction's heat depends on which reaction ran before it — the same
        // reason the oxygen is apportioned against a snapshot rather than taken in turn.
        //
        // The layer needs no such snapshot any more: nothing leaves it, so there is no share of its
        // heat to take. See [oxidise] on why a reaction may no longer put gas anywhere.
        val airMass = airMassAt(air, tile)
        val airHeat = airEnergy?.get(tile) ?: 0L

        // ── Oxidation: several consumers, one tile's oxygen ───────────────────────
        val oxygenHere = air[tile, Fluid.Oxygen]
        if (oxygenHere > 0L) {
            var wanted = 0L
            for (i in OXIDATIONS.indices) {
                val want = OXIDATIONS[i].demand(layer[tile, OXIDATIONS[i].reactant], kelvin)
                demands[i] = want
                wanted += want
            }
            if (wanted > 0L) {
                if (wanted <= oxygenHere) demands.copyInto(allowed) else apportionInto(demands, oxygenHere, allowed)

                for (i in OXIDATIONS.indices) {
                    if (allowed[i] <= 0L) continue
                    val reaction = OXIDATIONS[i]
                    val reacted = reaction.react(layer[tile, reaction.reactant], allowed[i], kelvin)
                    if (reacted.isNothing) continue

                    layer.add(tile, reaction.reactant, -reacted.reactant)
                    air.add(tile, Fluid.Oxygen, -reacted.oxygen)

                    // **Air → solid, and that is now the only direction an oxidation crosses in.**
                    // The product joins the layer whatever phase it would be free-standing — a
                    // burnt lump keeps its own CO2 — so the oxygen's mass and its share of the
                    // room's heat arrive and nothing goes back the other way. What used to be a
                    // second branch venting the product is [offGas]'s job now, at a tile that has
                    // air in it to receive it.
                    val carried = scaledRatio(reacted.oxygen, airMass, airHeat)
                    layer.add(tile, reaction.product, reacted.product)
                    layer.addEnergy(tile, carried)
                    airEnergy?.let { it[tile] -= carried }
                    toSolidMass += reacted.oxygen
                    toSolidEnergy += carried

                    released += applyEnthalpy(layer, tile, -reaction.enthalpy(reacted.reactant))
                }
            }
        }

        // ── Decomposition: heat alone, so nothing to allocate ─────────────────────
        //
        // No demand pass and no apportionment, because there is no shared reagent to run out of:
        // "no reagent, just heat" is exactly the statement that these cannot compete. A tile holding
        // two decomposing minerals runs both, in full.
        for (i in DECOMPOSITIONS.indices) {
            val reaction = DECOMPOSITIONS[i]
            val consumed = reaction.decomposed(layer[tile, reaction.reactant], kelvin)
            if (consumed <= 0L) continue

            val parts = reaction.split(consumed)
            layer.add(tile, reaction.reactant, -consumed)
            for (p in reaction.products.indices) {
                val species = reaction.products[p].first
                val mass = parts[p]
                if (mass <= 0L) continue
                layer.add(tile, species, mass)
            }

            released += applyEnthalpy(layer, tile, -reaction.enthalpy(consumed))
        }

        // ── Reduction: a solid reagent, and contention that is per species ────────
        //
        // The shape [oxidise] could not simply grow a row for. An oxidation's reagent is the tile's
        // oxygen and every row drinks from it, so that contention is one apportionment. A reduction's
        // reagent is a solid **in this layer**, and a tile may hold three different ones — so the
        // rows after the carbon have no claim whatever on the silicon, and pooling them would starve
        // reactions that were never in competition. Hence a demand-then-apportion per group rather
        // than per tile; the Jacobi rule is the same, the well is not.
        //
        // ⚠️ **A group reads the layer as it stands, so one pass can cascade.** Magnesium made by an
        // earlier group is available to a later one in the same pass, because `REDUCTIONS` is a chain
        // and its groups happen to fall in chain order. That is deterministic and it cannot break
        // conservation — every step is still a small fraction of its own oxide, so what cascades is a
        // second-order crumb — but it does mean the chain runs marginally faster than four separate
        // passes would. Stated because it is the kind of thing that reads as a bug later.
        for (g in REDUCTION_GROUPS.indices) {
            val group = REDUCTION_GROUPS[g]
            val reagentHere = layer[tile, group.reductant]
            if (reagentHere <= 0L) continue

            val rows = group.rows
            reductantDemands.fill(0L)
            var wanted = 0L
            for (i in rows.indices) {
                val catalyst = rows[i].catalyst
                val want = rows[i].demand(layer[tile, rows[i].oxide], if (catalyst == null) 0L else layer[tile, catalyst], kelvin)
                reductantDemands[i] = want
                wanted += want
            }
            if (wanted <= 0L) continue
            if (wanted <= reagentHere) {
                reductantDemands.copyInto(reductantAllowed)
            } else {
                apportionInto(reductantDemands, reagentHere, reductantAllowed)
            }

            for (i in rows.indices) {
                if (reductantAllowed[i] <= 0L) continue
                val reaction = rows[i]
                val done = reaction.react(layer[tile, reaction.oxide], reductantAllowed[i], kelvin)
                if (done.isNothing) continue

                // Both reagents leave the layer, and the products account for the whole of both —
                // see [Reduction.split], which is the one place this differs from a decomposition in
                // more than naming.
                layer.add(tile, reaction.oxide, -done.oxide)
                layer.add(tile, reaction.reductant, -done.reductant)

                val parts = reaction.split(done.total)
                for (p in reaction.products.indices) {
                    val species = reaction.products[p].first
                    val mass = parts[p]
                    if (mass <= 0L) continue
                    layer.add(tile, species, mass)
                }

                // Per kilogram of **oxide**, which is what the rate was a fraction of and what the
                // row's enthalpy is quoted against.
                released += applyEnthalpy(layer, tile, -reaction.enthalpy(done.oxide))
            }
        }
    }

    // Zero to gas, structurally and for ever: this function no longer has a path that puts matter
    // into [air]. Only [offGas] does, and only where there is air to put it in.
    return if (toSolidMass == 0L && toSolidEnergy == 0L && released == 0L) {
        ChemistryStep.NOTHING
    } else {
        ChemistryStep(toGasMass = 0L, toGasEnergy = 0L, toSolidMass, toSolidEnergy, released)
    }
}

/**
 * Puts [delta] of reaction energy into the matter at [tile] and reports what was actually put.
 *
 * Positive is a reaction warming what it happened to; negative is one cooling it. The two are the
 * same arithmetic and it is written once, because a sign convention with two implementations is a
 * sign convention with one bug.
 *
 * ⚠️ **An endothermic reaction may not drive a tile below zero energy**, which is below absolute
 * zero and would read back as a nonsensical temperature for as long as the matter sat there. It is
 * clamped, and the clamp is why this returns a value rather than being a statement: what the ledger
 * must hear is what was *taken*, not what was asked for.
 *
 * In practice the clamp is nearly unreachable and is a guard rather than a mechanism — a pass
 * converts a fraction of a per cent of the matter, so the energy it draws is a fraction of a per
 * cent of what the matter holds, and a reaction that cools its own feed simply drops below its onset
 * and stops. That is the whole loop a thermal decomposer exists to fight: calcining takes more
 * energy per kilogram than the rock holds at its own calcining temperature, so the element has to
 * keep supplying it or the reaction stalls.
 */
private fun applyEnthalpy(layer: StuffLayer, tile: TileIndex, delta: Long): Long {
    if (delta == 0L) return 0L
    val applied = if (delta < 0L) maxOf(delta, -layer.energyAt(tile)) else delta
    if (applied == 0L) return 0L
    layer.addEnergy(tile, applied)
    return applied
}

/**
 * Everything the tile's air weighs.
 *
 * The presence-bitmask walk, so it costs the six-or-so species actually in the room rather than the
 * width of the array — see `MassArray.forEachFluid`. It is only asked at a tile that has already
 * answered yes to being hot, having oxygen, and holding a reactant.
 */
private fun airMassAt(air: MassArray, tile: TileIndex): Long {
    var total = 0L
    air.forEachFluid(tile) { _, mass -> total += mass }
    return total
}

/**
 * The coldest any reaction of any kind starts at, so a cold tile is rejected without asking each one.
 *
 * Derived from **both** tables rather than written down, because a reaction added below a
 * hand-written constant would be a reaction that silently never ran — and with two tables that is
 * twice as easy to do and no easier to notice.
 */
private val LOWEST_ONSET: Int =
    minOf(OXIDATIONS.minOf { it.onsetKelvin }, LOWEST_DECOMPOSITION_ONSET, LOWEST_REDUCTION_ONSET)


/**
 * Volatiles leaving the matter that is carrying them, wherever the tile they are standing in will
 * have them — the other half of "[oxidise] may not put gas anywhere".
 *
 * A lump of comet ore is 8% water, 3% ammonia and 1% methane by mass, and for the whole life of the
 * game it carried them as though they were rock. Nothing asked whether a species that is a gas at
 * room temperature ought to still be riding a belt. This asks, once a pass, everywhere at once.
 *
 * ### The rule, and why it needs no rate
 *
 * A species leaves until the tile's gas is **saturated with it at the temperature of the matter it
 * is leaving** — [saturatedVapourDensity] is exactly the density at which a cell can hold no more
 * of a species as vapour, and [massAtReducedDensity] turns that into the mass a full tile will
 * take. What is already in the air counts against that ceiling, so a lump in a room that is already
 * thick with water vapour sheds nothing, and the same lump in a dry room sheds until it is not.
 *
 * That is a relaxation to equilibrium rather than a rate, which is [exchangeLayers]'s construction
 * and is here for the same reason: **a rate is a knob and equilibrium is not.** There is no number
 * in this function that anybody chose. A puddle in a sealed room evaporates until the room is
 * saturated and then stops, because that is what the dome says, not because a constant was tuned
 * until it looked right.
 *
 * ⚠️ **The temperature is the matter's, the pressure is the room's**, and the split is deliberate:
 * a wet rock in a vacuum chamber boils according to how hot *it* is against how hard the *room*
 * pushes back. Reading both off the air would make a hot lump in a cold room inert, which is the
 * one case where off-gassing is most obviously supposed to happen.
 *
 * ⚠️ **A species with no critical point on file has no liquid phase in this model and leaves in
 * full** — see [org.emerge.demo.outofspace.chem.CRITICAL], which holds five entries. Methane and
 * ammonia are gases here, unconditionally, so ore carrying them is ore carrying gas in a sack, and
 * this empties the sack. That is the honest reading of the equation of state and it is also, by
 * some distance, the largest thing this function does.
 *
 * ⛔ **No latent heat yet.** What leaves takes its share of the matter's warmth with it, the same
 * share [handOver] takes between a room and a pipe, but the *cost of the phase change itself* is
 * not charged — so evaporation here does not cool the thing evaporating. [
 * org.emerge.demo.outofspace.chem.cohesionEnergy] is the term that would, and wiring it in is a
 * separate piece of work with a ledger of its own. Until then a lump cannot chill itself back below
 * its own boiling point, and the saturation ceiling is the only thing stopping it.
 *
 * ### Where it may not happen
 *
 * [holdsAirOut] is asked first and answers for the tile, not for the matter: a bulkhead has no gas
 * cell, so there is nowhere for a volatile to go and it stays in the lump until the lump is
 * somewhere with a room around it. This is the guard `SealedTileGasTest` pins, and it is deliberately
 * the *only* place in the chemistry that consults the structure — one gate, on the one pass that
 * can put matter into the air.
 *
 * ⚠️ **Give it the layer whose contents are cargo**, exactly as [oxidise] requires and for the same
 * reason: what leaves here is booked by `solidBecameGas`, which closes the cargo identity against
 * the air one. The deck's own metal is fabric and is counted somewhere else.
 */
fun offGas(
    layer: StuffLayer,
    air: MassArray,
    airEnergy: EnergyArray?,
    holdsAirOut: (TileIndex) -> Boolean,
): ChemistryStep {
    var toGasMass = 0L
    var toGasEnergy = 0L

    // One buffer for the whole sweep rather than one per tile — [oxidise]'s reason exactly. Indexed
    // by [Fluid] ordinal because that is the space the answers live in, and it is a seventh of
    // [Species.COUNT].
    val leaving = LongArray(Fluid.COUNT)

    layer.forEachOccupiedTile { tile ->
        // The one structural question, asked before any arithmetic: is there a room here at all.
        if (holdsAirOut(tile)) return@forEachOccupiedTile

        val heldMass = layer.massAt(tile)
        if (heldMass <= 0L) return@forEachOccupiedTile
        val kelvin = layer.kelvinAt(tile)

        // ── What wants to leave, decided against the tile as it stands ────────────
        //
        // The presence-bitmask walk, so this costs the handful of species the matter actually
        // holds rather than the width of [Species]. **Decided here and applied below rather than
        // as it goes**, because taking mass out of the row that is being walked is exactly the
        // kind of thing that works until a species reaches zero.
        leaving.fill(0L)
        var total = 0L
        layer.forEachSpecies(tile) { species, held ->
            if (held <= 0L) return@forEachSpecies
            val fluid = species.fluid ?: return@forEachSpecies
            val room = vapourHeadroom(species, kelvin, air[tile, fluid])
            val release = if (held < room) held else room
            if (release <= 0L) return@forEachSpecies
            leaving[fluid.ordinal] = release
            total += release
        }
        if (total <= 0L) return@forEachOccupiedTile

        // ── And now it goes ───────────────────────────────────────────────────────
        for (fluid in Fluid.ALL) {
            val release = leaving[fluid.ordinal]
            if (release <= 0L) continue
            layer.add(tile, fluid.species, -release)
            air.add(tile, fluid, release)
        }

        // The heat rides along with it, as a share of what the matter held before any of it left —
        // read before the loop above for the reason every share in [oxidise] is read against a
        // snapshot. A hot lump that shed cold vapour would have mislaid its joules.
        val carried = scaledRatio(total, heldMass, layer.energyAt(tile))
        if (carried != 0L) {
            layer.addEnergy(tile, -carried)
            airEnergy?.let { it[tile] += carried }
        }
        toGasMass += total
        toGasEnergy += carried
    }

    return if (toGasMass == 0L && toGasEnergy == 0L) {
        ChemistryStep.NOTHING
    } else {
        ChemistryStep(toGasMass, toGasEnergy, toSolidMass = 0L, toSolidEnergy = 0L, releasedEnergy = 0L)
    }
}

/**
 * How much more of [species] a full tile at [kelvin] will hold as vapour, given [inAir] of it there.
 *
 * [Long.MAX_VALUE] means "as much as you have": either the species has no critical point on file
 * and so no liquid phase in this model, or the matter is hotter than its critical temperature,
 * where liquid and vapour stop being different things and there is nothing to saturate.
 */
private fun vapourHeadroom(species: Species, kelvin: Int, inAir: Long): Long {
    val temperatureR = reducedTemperature(kelvin, species) ?: return Long.MAX_VALUE
    val vapourR = saturatedVapourDensity(temperatureR, species) ?: return Long.MAX_VALUE
    val ceiling = massAtReducedDensity(vapourR, species, VolumeField.FULL, VolumeField.FULL)
        ?: return Long.MAX_VALUE
    return if (ceiling > inAir) ceiling - inAir else 0L
}

/**
 * A pass of every gas-phase fire over the whole of [air] — see
 * [org.emerge.demo.outofspace.chem.Combustion] for why this is a shape of its own.
 *
 * ### Nothing crosses a ledger
 *
 * Both reagents come out of [air] and every product goes back into it, so the cargo identity is
 * untouched and the air identity is untouched. **The total mass of a tile's gas is unchanged by
 * this function, exactly**, which is the strongest statement available about it and the one
 * `GasFireTest` makes. The only thing a pass reports is [ChemistryStep.releasedEnergy].
 *
 * ⚠️ **The tile's oxygen is contended, as in [oxidise], and by the same rule.** Every row is asked
 * what it wants against one snapshot before any oxygen is taken; only then is the supply handed out.
 * Hydrogen and methane in the same starved room both get a share, and which came first in the table
 * changes nothing but the rounding.
 *
 * ⚠️ **Temperatures are derived when they are not given**, for [diffuseFluid]'s reason: a caller
 * holding [airEnergy] knows them whether or not it passed them, and a fire that failed to start
 * because an argument was omitted would be a silent one. Read once per tile, before anything
 * reacts, so no row's rate depends on the heat an earlier row released.
 */
fun combust(air: MassArray, airEnergy: EnergyArray, kelvin: IntArray? = null): ChemistryStep {
    val tiles = air.data.size / Fluid.COUNT
    val temperature = kelvin ?: gasKelvin(airEnergy, heatCapacity(tiles, air))

    // Hoisted for the whole sweep rather than per tile — [oxidise]'s reason exactly.
    val demands = LongArray(COMBUSTION_COUNT)
    val allowed = LongArray(COMBUSTION_COUNT)

    var released = 0L

    for (i in 0 until tiles) {
        val tile = TileIndex(i)
        // Two compares, and between them they reject every tile in an ordinary vessel: a fire needs
        // an oxidiser and it needs to be hot enough for the most eager row in the table.
        val oxygenHere = air[tile, Fluid.Oxygen]
        if (oxygenHere <= 0L) continue
        val hot = temperature[i]
        if (hot < LOWEST_COMBUSTION_ONSET) continue

        demands.fill(0L)
        var wanted = 0L
        for (r in COMBUSTIONS.indices) {
            val reaction = COMBUSTIONS[r]
            val fuel = reaction.fuel.fluid ?: continue
            val want = reaction.demand(air[tile, fuel], hot)
            demands[r] = want
            wanted += want
        }
        if (wanted <= 0L) continue
        if (wanted <= oxygenHere) demands.copyInto(allowed) else apportionInto(demands, oxygenHere, allowed)

        for (r in COMBUSTIONS.indices) {
            if (allowed[r] <= 0L) continue
            val reaction = COMBUSTIONS[r]
            val fuel = reaction.fuel.fluid ?: continue
            val burned = reaction.react(air[tile, fuel], allowed[r], hot)
            if (burned.isNothing) continue

            air.add(tile, fuel, -burned.fuel)
            air.add(tile, Fluid.Oxygen, -burned.oxygen)

            // Both reagents are handed out across the products, so the tile's gas weighs what it
            // did — see [org.emerge.demo.outofspace.chem.Combustion.split].
            val parts = reaction.split(burned.total)
            for (p in reaction.products.indices) {
                val mass = parts[p]
                if (mass <= 0L) continue
                val product = reaction.products[p].first.fluid ?: continue
                air.add(tile, product, mass)
            }

            // Per kilogram of **fuel**, which is what the rate was a fraction of and what the row's
            // enthalpy is quoted against.
            released += applyAirEnthalpy(airEnergy, tile, -reaction.enthalpy(burned.fuel))
        }
    }

    return if (released == 0L) ChemistryStep.NOTHING
    else ChemistryStep(toGasMass = 0L, toGasEnergy = 0L, toSolidMass = 0L, toSolidEnergy = 0L, releasedEnergy = released)
}

/**
 * [applyEnthalpy]'s twin for the atmosphere, and clamped for the same reason: a reaction may not
 * drive a cell below zero energy, which is below absolute zero and would read back as a nonsensical
 * temperature for as long as the gas sat there.
 *
 * Every row of [COMBUSTIONS] is exothermic, so in practice this only ever adds — the clamp is a
 * guard against a future endothermic gas reaction rather than a mechanism.
 */
private fun applyAirEnthalpy(airEnergy: EnergyArray, tile: TileIndex, delta: Long): Long {
    if (delta == 0L) return 0L
    val applied = if (delta < 0L) maxOf(delta, -airEnergy[tile]) else delta
    if (applied == 0L) return 0L
    airEnergy[tile] += applied
    return applied
}
