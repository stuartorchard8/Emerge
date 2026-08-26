package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportionInto
import org.emerge.demo.outofspace.chem.vaporisationHeat
import org.emerge.demo.outofspace.chem.vapourMass
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.scaledRatio

/** What one scavenging pass lifted out of the atmosphere and onto the track. */
class ScavengeStep(val mass: Long, val energy: Long) {
    val isNothing: Boolean get() = mass == 0L && energy == 0L
}

/**
 * **Empty track picks frost up off the floor.**
 *
 * A vessel that cools below a species' triple point does not lose that species — it lands, and since
 * `a60499a3` it stays where it landed, because a solid is not a gradient and diffusion will not move
 * one. That leaves the matter visible, immobile and completely unreachable: the only thing that
 * could ever take it was the atmosphere, and the atmosphere is what put it there.
 *
 * So a length of empty rail standing in it lifts it. Frost is a deposit, and track is what the vessel
 * moves deposits with.
 *
 * ### ⛔ Solids only, and that is the whole rule
 *
 * [FluidPhase.Solid] and nothing else. Not vapour, which is the air and belongs to the room; and
 * **not liquid**, because a rail carries packets and a packet of puddle is not a thing. The boundary
 * is [Critical.triplePointKelvin], a measured property, so which species a given hold can be mined
 * for is a fact about how cold it is rather than a list somebody wrote.
 *
 * ### What crosses, and the one line that is easy to miss
 *
 * Mass leaves the air ledger and joins the cargo ledger, so the caller books `gasBecameSolid` — the
 * same crossing `oxidise` makes when iron takes oxygen out of a room, in the same direction.
 *
 * ⚠️ **The tile's cohesion has to be told as well, and nothing else in the tick would notice if it
 * were not.** [Stuff]'s cohesion is a statement about the matter in that tile; take a hundred
 * kilograms of frost out and say nothing, and the next [settleCohesion] recomputes, finds less bound
 * matter than the total says it is paying for, and makes up the difference **out of the room's
 * heat**. Removing frost would chill the room it came from — which is the free-refrigerator hole
 * arriving from the other side, and it would look exactly like a plausible cooling mechanic.
 *
 * ⛔ **No latent heat is charged or credited here, and that is correct rather than an omission.**
 * Nothing changes phase: the matter was bound in the air and it is bound on the track. What moves is
 * the binding *itself*, out of the field's books and into the cargo's, where matter is bound
 * implicitly and always has been. Charging for it would be inventing a phase change that did not
 * happen; the pair of adjustments here is a relocation and is meant to read as one.
 *
 * The sensible heat rides across in proportion to the mass, which is [handOver]'s construction and
 * is what keeps the tile at the temperature it was: the same share of the capacity leaves as of the
 * heat.
 */
fun scavengeFrost(
    grid: Grid,
    conduits: Conduits,
    rail: RailLayer,
    air: MassArray,
    airEnergy: EnergyArray,
    cohesion: EnergyArray,
): ScavengeStep {
    var liftedMass = 0L
    var liftedEnergy = 0L

    // Hoisted for the sweep. Indexed by [Fluid] ordinal, because that is the space the answers are
    // in; the packet is built in [Species] space at the end, once, from whatever survived.
    val wanted = LongArray(Fluid.COUNT)
    val allowed = LongArray(Fluid.COUNT)

    for (tile in grid.tiles) {
        // Track, finished, and with nothing already on it. A ghost is a frame with no metal in it
        // and picks nothing up — the same rule that stops one being a free length of track.
        if (conduits.at(Conduit.Rail, tile) == null) continue
        if (conduits.isGhost(Conduit.Rail, tile)) continue
        if (!rail.isEmpty(tile)) continue

        val tileMass = airMassOf(air, tile)
        if (tileMass <= 0L) continue
        val capacity = heatCapacityAt(air, tile)
        if (capacity <= 0L) continue
        val kelvin = (airEnergy[tile] / capacity).toInt()

        wanted.fill(0L)
        var total = 0L
        air.forEachFluid(tile) { fluid, mass ->
            if (mass <= 0L) return@forEachFluid
            val species = fluid.species
            // ⛔ **Below the triple point, and not `phaseAt(...) == Solid`.** That reads the *whole
            // tile*, and a tile only answers [FluidPhase.Solid] when it is packed to the solid
            // branch — some four and a half tonnes of water. Anything less is [FluidPhase
            // .Separating], frost with its own vapour above it, which is the ordinary case and the
            // one worth mining. What decides whether the condensed part is a solid is the
            // temperature alone: below [Critical.triplePointKelvin] there is no liquid phase at any
            // pressure, so whatever has condensed is frost.
            val triplePoint = CRITICAL[species]?.triplePointKelvin ?: return@forEachFluid
            if (kelvin >= triplePoint) return@forEachFluid
            // Only what is actually frost. A tile at its sublimation point holds both, and the
            // vapour above the frost is the room's.
            val frost = mass - vapourMass(mass, species, VolumeField.FULL, VolumeField.FULL, kelvin)
            if (frost <= 0L) return@forEachFluid
            wanted[fluid.ordinal] = frost
            total += frost
        }
        if (total <= 0L) continue

        // A packet is a packet. Over the cap the species share it out by [apportionInto], whose sum
        // is the target *by construction* — the one exact way to slice a pile without a species
        // rounding its way out of the mixture.
        if (total <= Capacity.PACKET_MASS) {
            wanted.copyInto(allowed)
        } else {
            apportionInto(wanted, Capacity.PACKET_MASS, allowed)
            total = Capacity.PACKET_MASS
        }

        // The heat that goes with it, taken against the tile as it stands — [handOver]'s share.
        val carried = scaledRatio(total, tileMass, airEnergy[tile])

        val packed = LongArray(Species.COUNT)
        var unbound = 0L
        for (fluid in Fluid.ALL) {
            val take = allowed[fluid.ordinal]
            if (take <= 0L) continue
            air.add(tile, fluid, -take)
            packed[fluid.species.ordinal] = take
            unbound += vaporisationHeat(take, fluid.species, kelvin)
        }

        airEnergy[tile] -= carried
        // ⚠️ The line the whole doc comment above is about. Less matter is bound here now, by
        // exactly the binding that walked away on the track.
        cohesion[tile] += unbound

        rail.put(tile, Mixture.of(packed, carried))
        liftedMass += total
        liftedEnergy += carried
    }

    return ScavengeStep(liftedMass, liftedEnergy)
}

/** Everything a tile's air weighs — the presence-bitmask walk, as in `AmbientChemistry`. */
private fun airMassOf(air: MassArray, tile: TileIndex): Long {
    var total = 0L
    air.forEachFluid(tile) { _, mass -> total += mass }
    return total
}
