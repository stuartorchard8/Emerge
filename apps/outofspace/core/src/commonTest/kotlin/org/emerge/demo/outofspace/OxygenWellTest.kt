package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.CARBON_BURN
import org.emerge.demo.outofspace.chem.COMBUSTIONS
import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.REDUCTIONS
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.SCALE
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.combust
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.oxidise
import org.emerge.demo.outofspace.world.oxygenScales
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **One tile's oxygen, and everybody who wants it** — increment 3 of `PLAN_unified_reactions.md`.
 *
 * `Reaction.kt` has said this since the day contention landed:
 *
 * > ⛔ **Never resolve contention by iteration order.** Whoever ran first would get the whole
 * > supply, which is a rule nobody can predict.
 *
 * It was enforced between the rows of a table and violated between the passes. `OutofspaceSim` ran
 * `oxidise(rails)`, then `oxidise(hoppers)`, then `combust`, each against the same array and each
 * apportioning whatever the one before had left behind. Three passes, each internally fair, and a
 * strict pecking order between them: **rail matter had first refusal on a tile's oxygen, hoppers
 * second, gas fires last.**
 *
 * The visible consequence: a room with burning carbon on a belt and methane in the air is a room
 * where the methane fire is starved by the belt, however much methane there is and however hot the
 * room. Nobody wrote that and no player could ever see why.
 *
 * ### What replaced it
 *
 * `oxygenScales` asks every consumer at a tile what it wants against one snapshot, before any oxygen
 * has been taken, and divides the well once. Each pass then takes its own share of its own demand.
 * The Jacobi argument is the file's own; the only thing that changed is that the well is now shared.
 */
class OxygenWellTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    /** A room, and a belt in it carrying [carbon] of carbon at [cargoKelvin]. */
    private fun world(
        oxygen: Long,
        methane: Long,
        carbon: Long,
        roomKelvin: Int,
        cargoKelvin: Int,
    ): Quad {
        val air = MassArray(tiles)
        air.add(tile, Fluid.Oxygen, oxygen)
        if (methane > 0L) air.add(tile, Fluid.Methane, methane)
        val airEnergy = EnergyArray(tiles)
        airEnergy[tile] = heatCapacityAt(air, tile) * roomKelvin

        val rail = StuffLayer.empty(tiles)
        if (carbon > 0L) {
            rail.add(tile, Species.Carbon, carbon)
            rail.setEnergy(tile, rail.heatCapacityAt(tile) * cargoKelvin)
        }
        return Quad(air, airEnergy, rail)
    }

    private class Quad(val air: MassArray, val airEnergy: EnergyArray, val rail: StuffLayer)

    private fun scalesFor(w: Quad): LongArray {
        val out = LongArray(tiles)
        val airKelvin = gasKelvin(w.airEnergy, heatCapacity(tiles, w.air))
        oxygenScales(listOf(w.rail), w.air, airKelvin, out)
        return out
    }

    // ── The well ─────────────────────────────────────────────────────────────

    @Test
    fun `plenty of oxygen is not a contention`() {
        // Nothing is short, so nobody is scaled. The common case, and it must cost the arithmetic
        // nothing: a full scale is the answer and every consumer takes its whole demand.
        val w = world(oxygen = 500 * kg, methane = 1 * kg, carbon = 1 * kg, roomKelvin = 1000, cargoKelvin = 900)
        assertEquals(SCALE, scalesFor(w)[tile.index])
    }

    @Test
    fun `a starved tile scales everybody by the same fraction`() {
        // ⚠️ The same fraction, not the same mass. Apportionment is proportional — a consumer that
        // wanted twice as much still gets twice as much — which is what makes "the oxygen attacks
        // the carbon first" an outcome of two rates rather than a priority somebody wrote.
        val w = world(oxygen = 1L, methane = 100 * kg, carbon = 100 * kg, roomKelvin = 1200, cargoKelvin = 1200)
        val scale = scalesFor(w)[tile.index]
        assertTrue(scale < SCALE, "a tile with one gram of oxygen was not short")
        assertTrue(scale >= 0L)
    }

    @Test
    fun `an empty tile divides nothing`() {
        val w = world(oxygen = 0L, methane = 0L, carbon = 0L, roomKelvin = 300, cargoKelvin = 300)
        assertEquals(SCALE, scalesFor(w)[tile.index])
    }

    // ── The bug ──────────────────────────────────────────────────────────────

    @Test
    fun `a belt no longer takes the oxygen a fire needed`() {
        // The case the increment exists for. A room short of oxygen, holding both a burning belt and
        // a methane fire, both well above their onsets.
        //
        // Run in pass order without a shared well, the belt goes first and takes what it wants from
        // the array; the fire then apportions the remainder against itself alone. With the well, both
        // are asked before either takes anything.
        val ordered = world(oxygen = 2 * kg, methane = 50 * kg, carbon = 50 * kg, roomKelvin = 1200, cargoKelvin = 1200)
        oxidise(ordered.rail, ordered.air, ordered.airEnergy)
        combust(ordered.air, ordered.airEnergy)
        val fireGotWhenQueued = ordered.air[tile, Fluid.CarbonDioxide]

        val shared = world(oxygen = 2 * kg, methane = 50 * kg, carbon = 50 * kg, roomKelvin = 1200, cargoKelvin = 1200)
        val scale = scalesFor(shared)
        oxidise(shared.rail, shared.air, shared.airEnergy, scale)
        combust(shared.air, shared.airEnergy, null, scale)

        // ⚠️ The CO2 in the air is the fire's alone: an oxidation's product joins the *cargo* layer,
        // never the air, so nothing the belt did can show up here. See `oxidise`'s ⛔ on that.
        val fireGotFromWell = shared.air[tile, Fluid.CarbonDioxide]
        assertTrue(
            fireGotFromWell > fireGotWhenQueued,
            "the fire got $fireGotFromWell from the shared well and $fireGotWhenQueued when queued " +
                "behind the belt — the well changed nothing",
        )
    }

    @Test
    fun `nobody can draw more oxygen than the tile has`() {
        // ⛔ The property that makes a scale safe to use in place of an exact apportionment. Each
        // consumer takes `floor(demand x scale)` and the scale is at most `supply / wanted`, so the
        // shares sum to no more than the supply however the rounding falls. Checked against a tile
        // that is oversubscribed many times over, which is where a scale that rounded the wrong way
        // would show.
        val w = world(oxygen = 3L, methane = 100 * kg, carbon = 100 * kg, roomKelvin = 1500, cargoKelvin = 1500)
        val before = w.air[tile, Fluid.Oxygen]
        val scale = scalesFor(w)
        oxidise(w.rail, w.air, w.airEnergy, scale)
        combust(w.air, w.airEnergy, null, scale)
        assertTrue(w.air[tile, Fluid.Oxygen] >= 0L, "the tile went into oxygen debt")
        assertTrue(w.air[tile, Fluid.Oxygen] <= before)
    }

    @Test
    fun `the order the passes run in stops mattering`() {
        // The statement of the fix, made directly: with a shared well, running the fire before the
        // belt gives the same answer as running the belt before the fire. Without one it could not,
        // which is exactly what made the old behaviour a rule nobody could predict.
        //
        // ⚠️ **The well is necessary and not sufficient, and this test is what found that.** The
        // shared oxygen made the two orders agree on the carbon and still disagree on the CO2,
        // because `combust` derives the room's temperature from `airEnergy` when it is not given
        // one — and an oxidation has already moved heat into the air by then. The fire's *rate* was
        // reading a room the belt had warmed. So the temperature has to be snapshotted before
        // anything reacts, exactly as the oxygen is, and `OutofspaceSim` derives it once and hands
        // it to every pass.
        //
        // ⛔ Passing `null` here would restore the difference. It is not tidier to omit it.
        fun run(fireFirst: Boolean): Pair<Long, Long> {
            val w = world(oxygen = 2 * kg, methane = 50 * kg, carbon = 50 * kg, roomKelvin = 1200, cargoKelvin = 1200)
            val scale = scalesFor(w)
            val airKelvin = gasKelvin(w.airEnergy, heatCapacity(tiles, w.air))
            if (fireFirst) {
                combust(w.air, w.airEnergy, airKelvin, scale)
                oxidise(w.rail, w.air, w.airEnergy, scale)
            } else {
                oxidise(w.rail, w.air, w.airEnergy, scale)
                combust(w.air, w.airEnergy, airKelvin, scale)
            }
            return w.rail[tile, Species.Carbon] to w.air[tile, Fluid.CarbonDioxide]
        }
        assertEquals(run(fireFirst = false), run(fireFirst = true))
    }

    @Test
    fun `two cargo layers at one tile are peers`() {
        // Rails before hoppers was the other half of the same bug, and the less visible one — both
        // are `oxidise`, so it read as one pass rather than two consumers.
        val air = MassArray(tiles)
        air.add(tile, Fluid.Oxygen, 2 * kg)
        val airEnergy = EnergyArray(tiles)
        airEnergy[tile] = heatCapacityAt(air, tile) * 1200

        val rail = StuffLayer.empty(tiles)
        val hopper = StuffLayer.empty(tiles)
        for (layer in listOf(rail, hopper)) {
            layer.add(tile, Species.Carbon, 50 * kg)
            layer.setEnergy(tile, layer.heatCapacityAt(tile) * 1200)
        }

        val scale = LongArray(tiles)
        oxygenScales(listOf(rail, hopper), air, gasKelvin(airEnergy, heatCapacity(tiles, air)), scale)
        oxidise(rail, air, airEnergy, scale)
        oxidise(hopper, air, airEnergy, scale)

        // Identical contents at identical temperatures asked for identical shares, so they must have
        // burned identical amounts. Under pass order the rail burned more, every time.
        assertEquals(rail[tile, Species.Carbon], hopper[tile, Species.Carbon])
        assertTrue(rail[tile, Species.Carbon] < 50 * kg, "neither layer reacted at all")
    }

    @Test
    fun `nothing outside the two tables the well divides between consumes oxygen`() {
        // `oxygenScales` counts demands from `OXIDATIONS` and `COMBUSTIONS` **by name**. Any other
        // consumer of a tile's oxygen would be invisible to the well and would take its share after
        // the division — which is the pecking order, back again, and silently.
        //
        // So the claim to hold is that those two are the only tables that want any. Stated here
        // rather than in `oxygenScales` because it is a fact about the other tables, and they are
        // the ones that can change under it.
        assertTrue(CARBON_BURN.oxygenUnits > 0, "an oxidation that wants no oxygen")
        assertTrue(COMBUSTIONS.all { it.oxygenUnits > 0 }, "a fire that wants no oxygen")
        for (d in DECOMPOSITIONS) {
            assertTrue(d.reactant != Species.Oxygen, "${d.reactant.name} cracks oxygen behind the well")
        }
        for (r in REDUCTIONS) {
            assertTrue(r.oxide != Species.Oxygen, "a reduction takes oxygen as its oxide")
            assertTrue(r.reductant != Species.Oxygen, "${r.oxide.name} is reduced *by* oxygen")
        }
        for (r in REACTIONS) {
            assertTrue(
                r.reagents.none { it.first == Species.Oxygen },
                "${r.principal.name} takes oxygen the well never counted",
            )
        }
    }
}
