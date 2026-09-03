package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.vaporisationHeat
import org.emerge.demo.outofspace.chem.vapourMass

/**
 * **The energy a field is holding in its own bonds** — the ledger's third term, and the thing that
 * makes condensing give back what boiling took.
 *
 * ### Why this exists as a *state* and not as an event
 *
 * `offGas` charges the latent heat when matter evaporates off a lump, because that is an event: mass
 * moves from one store to another and there is a moment to bill. **Condensing is not an event.**
 * Phase is derived from density and temperature, so a tile of vapour that cools *becomes* frost with
 * no code executing at all — which means there is no moment at which anything could credit the heat
 * back. Left there, an evaporate-condense cycle is a net energy sink of 2.26 MJ per kilogram of
 * water: a free refrigerator, as soon as anything closes the matter loop.
 *
 * So the cohesion is carried as a number *about the field* rather than as a transaction, recomputed
 * every fluid tick, and only its **change** is moved into or out of the thermal energy. Condensing
 * makes it more negative and the difference warms the tile; boiling makes it less negative and the
 * difference cools it. Neither direction needs to be detected, because the quantity is not a record
 * of what happened — it is a statement of what is.
 *
 * ⚠️ **The thermal array stays purely thermal, which is what keeps this from being circular.** The
 * obvious design — store thermal+cohesion together and derive temperature by subtracting the
 * cohesion — needs the temperature in order to compute the cohesion it is subtracting, and solving
 * that per tile per tick is both expensive and delicately conditioned. Keeping the cohesion beside
 * the energy rather than inside it makes every step explicit and costs one array.
 *
 * ⛔ **Not built on [org.emerge.demo.outofspace.chem.cohesionEnergy], and deliberately.** That
 * function is the equation of state's own attraction term and is the theoretically right place for
 * this to come from — its own documentation says so and says it is "not yet right, and knowingly
 * so". Measured against the boiling curve it is about five times too small for nitrogen and argon
 * and comes out with the **wrong sign** for water. It has never had a caller. Until somebody
 * reconciles it, the number used here is [vaporisationHeat], which is derived from the saturation
 * curve's own slope and is checked against measured values in `PhaseRealityTest`.
 *
 * That choice buys the property that matters: `offGas` charges [vaporisationHeat] on the way out and
 * this credits [vaporisationHeat] on the way back, so **the charge and the credit are the same
 * function of the same arguments** and the cycle closes by construction rather than by two
 * derivations agreeing.
 */
fun cohesionOf(
    masses: MassArray,
    kelvin: IntArray,
    volumes: VolumeField? = null,
): EnergyArray {
    val tiles = kelvin.size
    val out = EnergyArray(tiles)
    for (i in 0 until tiles) {
        val tile = TileIndex(i)
        val room = volumes?.at(tile) ?: VolumeField.FULL
        val hot = kelvin[i]
        var bound = 0L
        masses.forEachFluid(tile) { fluid, mass ->
            if (mass <= 0L) return@forEachFluid
            // What is *not* vapour is what is holding itself together — the same split
            // [diffuseFluid] uses to decide what may move, so the two can never disagree about how
            // much of a tile is condensed.
            val condensed = mass - vapourMass(mass, fluid.species, room, VolumeField.FULL, hot)
            if (condensed <= 0L) return@forEachFluid
            bound -= vaporisationHeat(condensed, fluid.species, hot)
        }
        out[tile] = bound
    }
    return out
}

/**
 * The cohesion of one tile **at a stated temperature**, rather than at the one its energy implies.
 *
 * Split out because [settleCohesion] has to ask this question of a dozen candidate temperatures per
 * tile and only one of them is the answer.
 */
private fun cohesionAt(masses: MassArray, tile: TileIndex, room: Int, kelvin: Int): Long {
    var bound = 0L
    masses.forEachFluid(tile) { fluid, mass ->
        if (mass <= 0L) return@forEachFluid
        val condensed = mass - vapourMass(mass, fluid.species, room, VolumeField.FULL, kelvin)
        if (condensed <= 0L) return@forEachFluid
        bound -= vaporisationHeat(condensed, fluid.species, kelvin)
    }
    return bound
}

/**
 * Splits each tile's energy between heat and bonds **self-consistently**, and reports what crossed.
 *
 * **Positive is energy released into the thermal pot** — matter condensed and gave its binding energy
 * up as heat — which is [ChemistryStep.releasedEnergy]'s sign convention and is booked the same way.
 *
 * ### ⛔ Why this solves rather than applies
 *
 * The obvious implementation is to recompute the cohesion, take the difference from last tick, and
 * move that into the thermal energy. **It oscillates, and violently.** Measured, on a kilogram of
 * steam cooled a quarter of a kelvin at a time: at 396 K a little condenses and releases enough heat
 * to put the tile at 412 K; at 412 K nothing is condensed, so the next step takes all of it back and
 * returns the tile to 396 K. Forever, with the amplitude *growing* as the tile descends — 6 MJ,
 * then 13, then 20, then 33 — and a net contribution over each pair of exactly zero. A latent heat
 * applied that way is not merely inaccurate, it is identically nothing.
 *
 * The reason is a feedback gain above one. Condensing releases far more energy than the temperature
 * step that triggered it: water's latent heat is 2.26 MJ/kg against a specific heat of 4.18 kJ/kg/K,
 * so condensing a tenth of a tile's water reverses about fifty kelvin of cooling. Any explicit
 * scheme that applies the whole difference will overshoot the condensation point and come back.
 *
 * So the split is **solved for** instead. The tile's total — heat plus bonds — is fixed by this
 * function, and the temperature is the one at which the two are consistent:
 *
 *     capacity·T + cohesion(T) = total
 *
 * ⚠️ **That is a monotone equation, which is the whole reason this is cheap and safe.** Raising the
 * temperature raises the thermal term and also raises the cohesion term — less is condensed, and
 * what still is holds less tightly — so the left side is strictly increasing and a bisection cannot
 * fail to converge or land on the wrong root. Thirteen halvings gets it to the kelvin.
 *
 * The physical reading of the answer is the plateau: while a tile is condensing, the solution sits
 * at the same temperature over a wide range of totals and the *condensed fraction* is what moves.
 * That is what a latent heat is, and it falls out of the equation rather than being arranged.
 */
fun settleCohesion(
    masses: MassArray,
    energies: EnergyArray,
    cohesion: EnergyArray,
    volumes: VolumeField? = null,
): Long {
    val tiles = cohesion.data.size
    val thermalMasses = thermalMass(tiles, masses)

    var released = 0L
    for (i in 0 until tiles) {
        val tile = TileIndex(i)
        // ⛔ **Thermal mass, not capacity.** A capacity is pre-divided by `CAPACITY_DIVISOR`, so it
        // reads zero for anything under a few milligrams — and this branch means *no matter at all*.
        // Told otherwise, a thin cell was declared empty and had its cohesion silently zeroed, which
        // is a real energy loss and one `released` never booked. See [kelvinOf].
        val thermal = thermalMasses[i]
        if (thermal <= 0L) {
            // No matter, so no heat and nothing to bind. Booking the stale cohesion away would mint
            // energy out of an empty tile.
            cohesion[tile] = 0L
            continue
        }
        val room = volumes?.at(tile) ?: VolumeField.FULL
        val was = energies[tile]
        val total = was + cohesion[tile]

        // ⚠️ **The cheap way out, and it is the overwhelmingly common case.** Dry air, or air far
        // above everything it holds, has no cohesion at any nearby temperature — one evaluation
        // says so and the tile is done.
        val plain = if (total > 0L) kelvinOf(total, thermal).toLong() else 0L
        if (cohesion[tile] == 0L && cohesionAt(masses, tile, room, plain.toInt()) == 0L) continue

        // f(K) = capacity·K + cohesion(K), strictly increasing. f(0) = cohesion(0), which is the
        // most negative it can be and so is at or below `total`; the upper bound clears the whole
        // latent range, which for a tile packed with water is worth some six hundred kelvin.
        var lo = 0L
        var hi = maxOf(0L, plain) + LATENT_HEADROOM_KELVIN
        while (lo < hi) {
            val mid = (lo + hi + 1L) / 2L
            if (energyAtKelvin(thermal, mid.toInt()) + cohesionAt(masses, tile, room, mid.toInt()) <= total) lo = mid else hi = mid - 1L
        }

        // Taken as the *residue* rather than as `cohesionAt(lo)`, so the two halves sum to the total
        // exactly whatever the kelvin rounding did. Energy is conserved by construction here, which
        // is the property a settling pass must not get wrong.
        val sensible = energyAtKelvin(thermal, lo.toInt())
        energies[tile] = sensible
        cohesion[tile] = total - sensible
        released += sensible - was
    }
    return released
}

/**
 * How far above the no-cohesion temperature [settleCohesion] looks for its root.
 *
 * A tile packed solid with water carries about six hundred kelvin of latent heat; five thousand is
 * eight times the worst case and costs three extra halvings, which is the right way round for a
 * bound that must never be wrong.
 */
private const val LATENT_HEADROOM_KELVIN = 5_000L
