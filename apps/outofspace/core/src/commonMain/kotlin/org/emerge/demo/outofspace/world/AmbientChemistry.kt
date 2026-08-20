package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.LOWEST_DECOMPOSITION_ONSET
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
    /**
     * Energy the reactions **made**, net — negative when they took more than they gave.
     *
     * A fifth number and a different kind from the other four: those are mass changing medium and
     * the heat that rode along with it, which is energy *moving*. This is energy appearing out of
     * chemical bonds or disappearing into them, so it belongs to neither medium's ledger and needs
     * telling on its own. Burning carbon is strongly negative — a fire is a source; calcining is
     * strongly positive the other way, and is why a decomposer's element has to keep working.
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
    var released = 0L

    // Allocated once for the whole sweep, not once per tile. A few longs is nothing; a few longs at
    // every occupied tile of every layer every pass is a shape of cost that only ever shows up as
    // "the chemistry is slow".
    val demands = LongArray(OXIDATIONS.size)
    val allowed = LongArray(OXIDATIONS.size)

    layer.forEachOccupiedTile { tile ->
        val kelvin = layer.kelvinAt(tile)
        // Where nearly every tile in the game stops, for one compare.
        if (kelvin < LOWEST_ONSET) return@forEachOccupiedTile

        // The tile as it was before anything reacted. **Every energy share below is a share of
        // these**, so no reaction's heat depends on which reaction ran before it — the same reason
        // the oxygen is apportioned against a snapshot rather than taken in turn.
        val heldMass = layer.massAt(tile)
        val heldEnergy = layer.energyAt(tile)
        val airMass = airMassAt(air, tile)
        val airHeat = airEnergy?.get(tile) ?: 0L

        /**
         * [intoAir] of [fluid] joins the tile's gas, of which [fromLayer] came out of the solid.
         *
         * The two are the same for a decomposition and differ for a combustion: burning carbon puts
         * a whole CO2 into the air but only the carbon *crossed* — the oxygen was already air and
         * still is. The ledger hears [fromLayer]; the tile's gas gets [intoAir]; and the heat that
         * travels is the share belonging to the mass that actually changed medium.
         */
        fun ventGas(fluid: Fluid, intoAir: Long, fromLayer: Long) {
            val carried = scaledRatio(fromLayer, heldMass, heldEnergy)
            air.add(tile, fluid, intoAir)
            if (carried != 0L) {
                layer.addEnergy(tile, -carried)
                airEnergy?.let { it[tile] += carried }
            }
            toGasMass += fromLayer
            toGasEnergy += carried
        }

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

                    val productFluid = reaction.product.fluid
                    if (productFluid != null) {
                        // Solid -> air. The whole product joins the gas; only the reactant crossed.
                        ventGas(productFluid, intoAir = reacted.product, fromLayer = reacted.reactant)
                    } else {
                        // Air → solid. The oxygen's mass and its share of the room's heat arrive;
                        // the reactant never left the layer, so only the oxygen crosses.
                        val carried = scaledRatio(reacted.oxygen, airMass, airHeat)
                        layer.add(tile, reaction.product, reacted.product)
                        layer.addEnergy(tile, carried)
                        airEnergy?.let { it[tile] -= carried }
                        toSolidMass += reacted.oxygen
                        toSolidEnergy += carried
                    }

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
                val fluid = species.fluid
                if (fluid != null) ventGas(fluid, intoAir = mass, fromLayer = mass) else layer.add(tile, species, mass)
            }

            released += applyEnthalpy(layer, tile, -reaction.enthalpy(consumed))
        }
    }

    return if (toGasMass == 0L && toGasEnergy == 0L && toSolidMass == 0L && toSolidEnergy == 0L && released == 0L) {
        ChemistryStep.NOTHING
    } else {
        ChemistryStep(toGasMass, toGasEnergy, toSolidMass, toSolidEnergy, released)
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
private val LOWEST_ONSET: Int = minOf(OXIDATIONS.minOf { it.onsetKelvin }, LOWEST_DECOMPOSITION_ONSET)
