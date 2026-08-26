package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.criticalOf
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportionInto
import org.emerge.demo.outofspace.chem.vaporisationHeat
import org.emerge.demo.outofspace.chem.vapourMass
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * **Frost lying on a plate, scraped up into a machine's store.**
 *
 * A vessel that cools below a species' triple point does not lose that species — it lands, and since
 * `a60499a3` it stays where it landed, because a solid is not a gradient and diffusion will not move
 * one. That leaves the matter visible, immobile and otherwise unreachable: the only thing that could
 * take it was the atmosphere, and the atmosphere is what put it there.
 *
 * ⛔ **This is deliberately not what `910163f9` was.** That version let every empty length of finished
 * rail lift frost off its own tile, and it was reverted (`86578725`) because a rail network standing
 * in a cold vessel then spends itself collecting the ice around it instead of carrying what it was
 * drawn for — free material off the floor for the price of some track, and self-construction turned
 * into a temperature-management chore. The route back to cargo is a **machine** the player has to
 * build, stand somewhere cold, and feed with a belt like anything else, and it takes frost only from
 * the tiles it is standing on.
 *
 * ### ⛔ Solids only, and that is the whole rule
 *
 * Not vapour, which is the air and belongs to the room; and **not liquid**, because what leaves here
 * becomes a packet and a packet of puddle is not a thing. The boundary is
 * [org.emerge.demo.outofspace.chem.Critical.triplePointKelvin], a measured property, so which
 * species a given hold can be mined for is a fact about how cold it is rather than a list somebody
 * wrote.
 *
 * ⚠️ **The test is `kelvin < triplePointKelvin`, and NOT `phaseAt(...) == Solid`.** That reads the
 * *whole tile*, and a tile only answers [FluidPhase.Solid] when it is packed to the solid branch —
 * some four and a half tonnes of water. Anything less is [FluidPhase.Separating], frost with its own
 * vapour above it, which is the ordinary case and the one worth mining. Below the triple point there
 * is no liquid phase at any pressure, so whatever has condensed is frost and the temperature alone
 * decides it.
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
 * heat**. Lifting frost would chill the room it came from — which is the free-refrigerator hole
 * arriving from the other side, and it would look exactly like a plausible cooling mechanic.
 *
 * ⛔ **No latent heat is charged or credited here, and that is correct rather than an omission.**
 * Nothing changes phase: the matter was bound in the air and it is bound in the store. What moves is
 * the binding *itself*, out of the field's books and into the cargo's, where matter is bound
 * implicitly and always has been. Charging for it would be inventing a phase change that did not
 * happen; the pair of adjustments here is a relocation and is meant to read as one.
 *
 * The sensible heat rides across in proportion to the **heat capacity** that left, which is what
 * keeps the tile at the temperature it was. ⚠️ Not in proportion to the mass: this takes one species
 * out of a mixture rather than a slice of all of it, and water carries four times the heat of the
 * same weight of nitrogen, so a mass share would leave the room too hot. It arrives as the returned
 * mixture's [Mixture.energy], so a store that takes it gets cold cargo rather than a free warm-up.
 *
 * @param tiles the footprint to scrape — walked in order, each tile stripped as far as [budget]
 *   reaches, so a plate standing in a drift clears the tiles nearest the front of its own footprint
 *   first and comes back for the rest next pass.
 * @param budget the most that may be lifted in one pass, a packet by default: the same quantum the
 *   rest of the material network is measured in, and the reason a plate takes a visible while to
 *   clear a deep drift instead of inhaling it in a tick.
 * @return what was lifted, carrying its share of the air's heat. [Mixture.EMPTY] if there was no
 *   frost to take, in which case nothing was written to any field.
 */
fun liftFrost(
    tiles: Array<TileIndex>,
    air: MassArray,
    airEnergy: EnergyArray,
    cohesion: EnergyArray,
    budget: Long = Capacity.PACKET_MASS,
): Mixture {
    if (budget <= 0L) return Mixture.EMPTY
    var remaining = budget
    var lifted = 0L
    var carriedTotal = 0L
    val packed = LongArray(Species.COUNT)

    // Hoisted for the sweep. Indexed by [Fluid] ordinal, because that is the space the answers are
    // in; the mixture is built in [Species] space at the end, once, from whatever survived.
    val wanted = LongArray(Fluid.COUNT)
    val allowed = LongArray(Fluid.COUNT)

    for (tile in tiles) {
        if (remaining <= 0L) break

        // Empty tiles fall out here: nothing in a tile is nothing to warm, and the temperature
        // below is a divide by this.
        val capacity = heatCapacityAt(air, tile)
        if (capacity <= 0L) continue
        val kelvin = (airEnergy[tile] / capacity).toInt()

        wanted.fill(0L)
        var total = 0L
        air.forEachFluid(tile) { fluid, mass ->
            if (mass <= 0L) return@forEachFluid
            val species = fluid.species
            val triplePoint = criticalOf(species)?.triplePointKelvin ?: return@forEachFluid
            if (kelvin >= triplePoint) return@forEachFluid
            // Only what is actually frost. A tile at its sublimation point holds both, and the
            // vapour above the frost is the room's.
            val frost = mass - vapourMass(mass, species, VolumeField.FULL, VolumeField.FULL, kelvin)
            if (frost <= 0L) return@forEachFluid
            wanted[fluid.ordinal] = frost
            total += frost
        }
        if (total <= 0L) continue

        // Over what is left of the budget the species share it out by [apportionInto], whose sum is
        // the target *by construction* — the one exact way to slice a pile without a species
        // rounding its way out of the mixture. ⚠️ Never species-by-species: see
        // `reference_oos_microgram_deadlock`, where N truncating divides leave a site N−1 units short
        // of a bill it then never reaches.
        if (total <= remaining) {
            wanted.copyInto(allowed)
        } else {
            apportionInto(wanted, remaining, allowed)
            total = remaining
        }

        var unbound = 0L
        var takenCapacity = 0L
        for (fluid in Fluid.ALL) {
            val take = allowed[fluid.ordinal]
            if (take <= 0L) continue
            air.add(tile, fluid, -take)
            packed[fluid.species.ordinal] += take
            unbound += vaporisationHeat(take, fluid.species, kelvin)
            // The whole product first and the divisor at the end, exactly as [heatCapacityAt] does
            // it: dividing per species rounds a trace gas out of its own capacity.
            takenCapacity += take * fluid.species.specificHeat
        }
        takenCapacity /= Budget.CAPACITY_DIVISOR

        // ⚠️ **The share of the tile's HEAT CAPACITY that walked off, not the share of its mass.**
        // Those are the same number only for a slice of everything in the tile, and this is never
        // that — it takes one species out of a mixture, chosen by temperature. A kilogram of water
        // carries four times the heat of a kilogram of nitrogen, so a mass share leaves the room
        // holding far too much energy for the matter still in it and the room gets *hotter* for
        // having been swept. Measured on 2 kg of frost in 20 kg of nitrogen: 200 K in, 255 K out.
        // A free heater, and the exact mirror of the free refrigerator the cohesion line below is
        // there to stop.
        val carried = scaledRatio(takenCapacity, capacity, airEnergy[tile])

        airEnergy[tile] -= carried
        // ⚠️ The line the whole doc comment above is about. Less matter is bound here now, by exactly
        // the binding that walked away into the store.
        cohesion[tile] += unbound

        remaining -= total
        lifted += total
        carriedTotal += carried
    }

    if (lifted <= 0L) return Mixture.EMPTY
    return Mixture.of(packed, carriedTotal)
}
