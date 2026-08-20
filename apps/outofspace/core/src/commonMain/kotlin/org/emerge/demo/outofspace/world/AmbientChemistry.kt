package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.OXIDATIONS
import org.emerge.demo.outofspace.chem.Oxidation
import org.emerge.demo.outofspace.chem.apportionInto
import org.emerge.demo.outofspace.chem.fluid
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
 */
class ChemistryStep(
    val toGasMass: Long,
    val toGasEnergy: Long,
    val toSolidMass: Long,
    val toSolidEnergy: Long,
) {
    val isNothing: Boolean
        get() = toGasMass == 0L && toGasEnergy == 0L && toSolidMass == 0L && toSolidEnergy == 0L

    companion object {
        val NOTHING = ChemistryStep(0L, 0L, 0L, 0L)
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
 * ### What crosses, and what has to be told
 *
 * Carbon **leaves the cargo ledger and joins the air ledger**; the oxygen iron takes does the
 * reverse. Those are two different identities — `inTransit + vented + built == extracted +
 * baselineCargo` on one side, `atmosphere + airVented == injected + baselineAir` on the other —
 * and neither knows about the other, so the caller has to book both crossings. See [ChemistryStep].
 *
 * The heat goes with the matter, in whichever direction it went: a share of the tile's thermal
 * energy proportional to the mass leaving, which is the same construction [handOver] uses between a
 * room and a pipe. It is not optional — a hot lump that turned into cold gas would have lost its
 * joules somewhere no gauge could see.
 *
 * ⚠️ **Both shares are taken against a snapshot of the tile**, read before any reaction has touched
 * it. Each reaction taking its share of what the previous one left would be arithmetically fine and
 * order-dependent, which is the one thing the demand pass above exists to prevent.
 *
 * ⚠️ **Give it the layer whose contents are cargo.** Rail and buffer contents are counted by
 * `cargoMass`; the deck's matter and the conduits' own metal are *fabric*, counted by `builtMass`,
 * and burning those closes a different identity that nothing here writes. That is why this takes a
 * layer rather than sweeping all of them: which ledger the matter belongs to is the caller's
 * knowledge, not this function's.
 */
fun oxidise(layer: StuffLayer, air: MassArray, airEnergy: EnergyArray?): ChemistryStep {
    var toGasMass = 0L
    var toGasEnergy = 0L
    var toSolidMass = 0L
    var toSolidEnergy = 0L

    // Allocated once for the whole sweep, not once per tile. Two longs is nothing; two longs at
    // every occupied tile of every layer every pass is a shape of cost that only ever shows up as
    // "the chemistry is slow".
    val demands = LongArray(OXIDATIONS.size)
    val allowed = LongArray(OXIDATIONS.size)

    layer.forEachOccupiedTile { tile ->
        val kelvin = layer.kelvinAt(tile)
        // Where nearly every tile in the game stops, for one compare.
        if (kelvin < LOWEST_ONSET) return@forEachOccupiedTile

        val oxygenHere = air[tile, Fluid.Oxygen]
        if (oxygenHere <= 0L) return@forEachOccupiedTile

        // ── Demand, against a snapshot ────────────────────────────────────────────
        var wanted = 0L
        for (i in OXIDATIONS.indices) {
            val want = OXIDATIONS[i].demand(layer[tile, OXIDATIONS[i].reactant], kelvin)
            demands[i] = want
            wanted += want
        }
        if (wanted <= 0L) return@forEachOccupiedTile

        if (wanted <= oxygenHere) {
            demands.copyInto(allowed)
        } else {
            apportionInto(demands, oxygenHere, allowed)
        }

        // The tile as it was before anything reacted — every energy share below is a share of these,
        // so no reaction's heat depends on which reaction ran before it.
        val heldMass = layer.massAt(tile)
        val heldEnergy = layer.energyAt(tile)
        val airMass = airMassAt(air, tile)
        val airHeat = airEnergy?.get(tile) ?: 0L

        // ── Reaction, against the allowance ───────────────────────────────────────
        for (i in OXIDATIONS.indices) {
            if (allowed[i] <= 0L) continue
            val reaction = OXIDATIONS[i]
            val reacted = reaction.react(layer[tile, reaction.reactant], allowed[i], kelvin)
            if (reacted.isNothing) continue

            layer.add(tile, reaction.reactant, -reacted.reactant)
            air.add(tile, Fluid.Oxygen, -reacted.oxygen)

            val productFluid = reaction.product.fluid
            if (productFluid != null) {
                // Solid → air. The reactant's mass and its share of the lump's heat both leave.
                val carried = scaledRatio(reacted.reactant, heldMass, heldEnergy)
                layer.addEnergy(tile, -carried)
                air.add(tile, productFluid, reacted.product)
                airEnergy?.let { it[tile] += carried }

                toGasMass += reacted.reactant
                toGasEnergy += carried
            } else {
                // Air → solid. The oxygen's mass and its share of the room's heat both arrive; the
                // reactant never left the layer, so only the oxygen crosses a ledger.
                val carried = scaledRatio(reacted.oxygen, airMass, airHeat)
                layer.add(tile, reaction.product, reacted.product)
                layer.addEnergy(tile, carried)
                airEnergy?.let { it[tile] -= carried }

                toSolidMass += reacted.oxygen
                toSolidEnergy += carried
            }
        }
    }

    return if (toGasMass == 0L && toGasEnergy == 0L && toSolidMass == 0L && toSolidEnergy == 0L) {
        ChemistryStep.NOTHING
    } else {
        ChemistryStep(toGasMass, toGasEnergy, toSolidMass, toSolidEnergy)
    }
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
 * The coldest any reaction starts at, so a cold tile is rejected without asking each one.
 *
 * Derived from [OXIDATIONS] rather than written down, because a reaction added below this and a
 * constant left above it would be a reaction that silently never runs.
 */
private val LOWEST_ONSET: Int = OXIDATIONS.minOf { it.onsetKelvin }
