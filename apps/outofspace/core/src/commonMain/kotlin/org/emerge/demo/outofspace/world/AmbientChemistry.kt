package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.burn
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What a pass of ambient chemistry moved out of the solids and into the air.
 *
 * Both halves, always. [mass] is a fact the *cargo* ledger has to hear and [energy] is a fact the
 * *air* ledger has to hear, and they are returned together because the one call that books them —
 * `solidBecameGas` — takes both and every path that reaches it must have both. A step that reported
 * only its mass would close one identity and quietly break the other, which is the failure mode the
 * mineral vaporizer lived with for its whole life.
 */
class ChemistryStep(val mass: Long, val energy: Long) {
    companion object {
        val NOTHING = ChemistryStep(0L, 0L)
    }
}

/**
 * One pass of `C + O₂ → CO₂` over everything [layer] holds — increment 1 of
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
 * at all. Then: is there carbon here (one bitmask-driven lookup, not 165), is there oxygen in the
 * air above it (one lookup in a [Fluid]-wide array since increment 0), and is the matter hot enough
 * (one compare against the ignition point). Only a tile that answers yes three times costs anything
 * more.
 *
 * ### What crosses, and what has to be told
 *
 * The carbon **leaves the cargo ledger and joins the air ledger**, and those are two different
 * identities: `inTransit + vented + built == extracted + baselineCargo` on one side, `atmosphere +
 * airVented == injected + baselineAir` on the other. Neither knows about the other, so the caller
 * has to book the crossing — see [ChemistryStep]. The oxygen needs no such term, because it was
 * already air and the CO₂ it becomes part of is still air.
 *
 * The heat goes with it. A share of the tile's thermal energy proportional to the mass leaving is
 * handed to the tile's gas, which is the same construction [handOver] uses between a room and a
 * pipe and is not optional: a hot lump that turned into cold gas would have lost its joules
 * somewhere no gauge could see.
 *
 * ⚠️ **Give it the layer whose contents are cargo.** Rail and buffer contents are counted by
 * `cargoMass`; the deck's matter and the conduits' own metal are *fabric*, counted by `builtMass`,
 * and burning those closes a different identity that nothing here writes. That is why this takes a
 * layer rather than sweeping all of them: which ledger the matter belongs to is the caller's
 * knowledge, not this function's.
 *
 * ⛔ **One reaction only.** The moment a second reaction wants the same tile's oxygen, this loop is
 * wrong — it would hand the whole supply to whichever ran first, which is a rule set by iteration
 * order. Increment 2 replaces that with a demand pass and [org.emerge.demo.outofspace.chem.apportion];
 * do not add the second consumer before it.
 */
fun burnCarbon(layer: StuffLayer, air: MassArray, airEnergy: EnergyArray?): ChemistryStep {
    var movedMass = 0L
    var movedEnergy = 0L

    layer.forEachOccupiedTile { tile ->
        val carbonHere = layer[tile, Species.Carbon]
        if (carbonHere > 0L) {
            val oxygenHere = air[tile, Fluid.Oxygen]
            if (oxygenHere > 0L) {
                val burned = burn(carbonHere, oxygenHere, layer.kelvinAt(tile))
                if (!burned.isNothing) {
                    // Read before anything is taken: the share of the lump's heat that leaves with
                    // the carbon is a share of what the lump had *before* it got lighter.
                    val heldMass = layer.massAt(tile)
                    val carried = scaledRatio(burned.carbon, heldMass, layer.energyAt(tile))

                    layer.add(tile, Species.Carbon, -burned.carbon)
                    layer.addEnergy(tile, -carried)

                    air.add(tile, Fluid.Oxygen, -burned.oxygen)
                    air.add(tile, Fluid.CarbonDioxide, burned.carbonDioxide)
                    airEnergy?.let { it[tile] += carried }

                    movedMass += burned.carbon
                    movedEnergy += carried
                }
            }
        }
    }

    return if (movedMass == 0L && movedEnergy == 0L) ChemistryStep.NOTHING else ChemistryStep(movedMass, movedEnergy)
}
