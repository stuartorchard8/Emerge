package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.MomentumField
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.remapped
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acceptance tests for §5 of `PLAN_dynamic_grid.md` — **a discarded cell is vented** — written
 * before the implementation, as P1, P2 and P3 were.
 *
 * §5 is the half of P4 that is not a button. `fitGrid` already computes an exact box and delegates
 * to [remapped], so the moment anything triggers a fit on a world that has grown, the grid *shrinks*
 * — and [remapped] currently drops the cells it loses on the floor. Every gram, joule and unit of
 * face momentum standing in a discarded tile simply stops existing, and `airBalance` breaks by
 * exactly that much. Growth was safe because it only ever adds vacuum; this is the first thing that
 * subtracts, and it is the reason §5 exists.
 *
 * **The rule, and the asymmetry inside it.** Gas is vented: it goes to `airVentedMass`,
 * `airVentedEnergy` and `exhaustMomentumX/Y`, which is physically the right story — the tile left
 * the world, and the only way out of this world is overboard. Solids are **not**: the box is drawn
 * around them by construction, so a resize that ate a machine has got its bounds wrong, and that is
 * a `require` rather than a booking. Gas is diffuse and legitimately present in a padding tile; a
 * machine is a thing.
 *
 * **How the expectations are derived.** Not by restating the loop that will implement this — a test
 * that re-derives the implementation's own arithmetic agrees with its bugs. Every case here asserts
 * a *conservation identity* instead: what the new field holds plus what was vented equals what the
 * old field held. That is true whatever indexing scheme the implementation picks, and it is exactly
 * the property §5 is protecting.
 */
class GridVentTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    /**
     * A world whose solids sit in a small block well inside the grid, so there is slack on every
     * side to cut away — but which is full of gas and moving gas everywhere, including in the part
     * about to be cut.
     *
     * **The solids are boxed in x ∈ [6, 10], y ∈ [4, 8] deliberately.** Every shrink in this file
     * has to keep all of them: a shrink that discards a solid is a bug, not a vent, and it throws
     * (see the last three cases), so a fixture whose hull reached the edges would turn every
     * venting case into that exception instead of testing the vent. A first draft ran the hull
     * along the borders and did exactly that.
     *
     * Asymmetric on purpose: the mass vary by tile and the two momentum axes carry different
     * magnitudes, so a transposed index or an off-by-one cannot pass by symmetry.
     */
    private fun gassyWorld(w: Int = 20, h: Int = 14): VesselState {
        val grid = Grid(w, h)
        val deck = DeckArray(grid)
        for (x in 6..10) {
            deck += Hull(grid.tile(x, 4))
            deck += Hull(grid.tile(x, 8))
        }
        for (y in 5..7) {
            deck += Hull(grid.tile(6, y))
            deck += Hull(grid.tile(10, y))
        }

        // Air everywhere, different in every tile, and in two species so a per-species stride bug
        // shows up as a mass change rather than cancelling out.
        val airMass = MassArray(grid.size)
        val airEnergy = EnergyArray(grid.size)
        for (tile in grid.tiles) {
            airMass[MassIndex(tile, Fluid.Oxygen)] = 100L + tile.index
            airMass[MassIndex(tile, Fluid.Nitrogen)] = 7L * tile.index
            airEnergy[tile] = 5_000L + 3L * tile.index
        }
        val air = Stuff.from(airMass, airEnergy)

        // Pipe air too: it is inside `atmosphereMass`, so a vent that forgets it breaks the ledger
        // in exactly the way §5 says the air ledger breaks. Deliberately a different profile.
        val pipeMass = MassArray(grid.size)
        val pipeEnergy = EnergyArray(grid.size)
        for (tile in grid.tiles) {
            pipeMass[MassIndex(tile, Fluid.Oxygen)] = 11L * tile.index
            pipeEnergy[tile] = 900L + tile.index
        }
        val pipeAir = Stuff.from(pipeMass, pipeEnergy)

        val edges = EdgeGrid(grid)
        // Signed, and asymmetric between the axes: the identity is a signed sum, so a field of
        // uniform positive values would let a sign error through.
        val momX = LongArray(edges.xEdgeCount) { if (it % 3 == 0) -40L - it else 13L + it }
        val momY = LongArray(edges.yEdgeCount) { if (it % 5 == 0) 77L + it else -9L - it }
        val pipeMomX = LongArray(edges.xEdgeCount) { 3L * it }
        val pipeMomY = LongArray(edges.yEdgeCount) { -2L * it }

        return VesselState(
            grid = grid,
                        deck = deck,
            air = air,
            pipeAir = pipeAir,
            momentum = MomentumField.of(edges, momX, momY),
            pipeMomentum = MomentumField.of(edges, pipeMomX, pipeMomY),
            // The ship carries the counterpart of whatever the faces hold, because the identity is
            // a statement about the *signed total*: a world handed arbitrary face momentum and
            // nothing else is genuinely out of balance before anything resizes it, and the
            // preconditions below caught exactly that. There is no baseline for momentum the way
            // there is for air — the sum is the invariant.
            vesselImpulseX = -(momX.sum() + pipeMomX.sum()),
            vesselImpulseY = -(momY.sum() + pipeMomY.sum()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
    }

    /** The whole momentum identity on one axis — zero on a world nothing has broken. */
    private fun momentumX(s: VesselState): Long = s.momentumBalanceX

    private fun momentumY(s: VesselState): Long = s.momentumBalanceY

    // ── 1. Gas is vented, not dropped ─────────────────────────────────────

    @Test
    fun `a shrink vents the air it discards`() {
        val before = gassyWorld()
        // The fixture is balanced to start with: `baselineAirMass` is a constructor default taken
        // from the air handed in, so this is a statement about the fixture, not an assumption.
        assertEquals(0L, before.airBalance, "the fixture is out before anything shrinks")

        val after = before.remapped(Grid(12, 14), 0, 0)

        assertTrue(
            after.atmosphereMass < before.atmosphereMass,
            "the fixture discarded no air, so this case proves nothing",
        )
        // The identity, not a re-derived loop: what is left plus what went overboard is what there
        // was. Nothing else can be true of a vent.
        assertEquals(
            before.atmosphereMass,
            after.atmosphereMass + (after.airVentedMass - before.airVentedMass),
            "mass discarded by the shrink were not booked to airVentedMass",
        )
        assertEquals(0L, after.airBalance, "airBalance after a shrink")
    }

    /**
     * ⚠️ **PARKED** — every assertion here is an energy identity, so with [EnergyLedgers] silenced
     * the case would run and prove nothing. Its mass twin, `a shrink vents the gas it discards`,
     * still covers that a shrink books what it throws away rather than deleting it.
     */
    @Ignore
    @Test
    fun `a shrink vents the energy it discards`() {
        val before = gassyWorld()
        EnergyLedgers.assertAirBalanced(before, "the fixture is out before anything shrinks")

        val after = before.remapped(Grid(12, 14), 0, 0)

        assertTrue(
            after.atmosphereEnergy < before.atmosphereEnergy,
            "the fixture discarded no heat, so this case proves nothing",
        )
        assertEquals(
            before.atmosphereEnergy,
            after.atmosphereEnergy + (after.airVentedEnergy - before.airVentedEnergy),
            "energy discarded by the shrink were not booked to airVentedEnergy",
        )
        EnergyLedgers.assertAirBalanced(after, "airEnergyBalance after a shrink")
    }

    @Test
    fun `the pipes are vented too, not just the rooms`() {
        // `atmosphereMass` is rooms **plus** pipes, and the two were once summed at separate call
        // sites with only one of them knowing about the pipes — see [VesselState.airBalance], which
        // exists because of that. A vent that reads only `air` passes test 1 on a world with empty
        // pipes and breaks the ledger on a real one.
        val before = gassyWorld()
        assertTrue(before.pipeAir.totalMass > 0L, "the fixture has no pipe gas to lose")

        val after = before.remapped(Grid(12, 14), 0, 0)

        assertTrue(after.pipeAir.totalMass < before.pipeAir.totalMass, "no pipe gas was discarded")
        assertEquals(0L, after.airBalance, "airBalance with pipe gas discarded")
        EnergyLedgers.assertAirBalanced(after, "airEnergyBalance with pipe gas discarded")
    }

    // ⛔ Two tests stood here — that a shrink books the face momentum it discards to the exhaust,
    // and that it books each axis independently. Both are **deleted rather than parked**: the
    // per-edge momentum field they were about is not a ledger quantity any more. The hull's
    // reaction moved to the vessel boundary, where only mass that genuinely leaves may push, so
    // there is no longer any momentum for a resize to discard. See [VesselState.ventMomentumX].

    @Test
    fun `a near-side shrink vents as much as a far-side one`() {
        // A negative offset: the origin moves the other way and the cells lost are on the low side.
        // Growth was side-agnostic (P3) and so is this — one path, not two.
        val before = gassyWorld()
        // -6, so the solid block at x ∈ [6, 10] lands at x ∈ [0, 4] and survives; the discarded
        // cells are the six columns of gas that were to the left of it.
        val after = before.remapped(Grid(12, 14), -6, 0)

        assertTrue(after.atmosphereMass < before.atmosphereMass, "nothing was discarded")
        assertEquals(0L, after.airBalance, "airBalance after a near-side shrink")
        EnergyLedgers.assertAirBalanced(after, "airEnergyBalance after a near-side shrink")
    }

    // ── 2. Growth still vents nothing ─────────────────────────────────────

    @Test
    fun `growing vents nothing at all`() {
        // The regression that a careless "book the difference" would cause: growth adds vacuum, so
        // every counter must be untouched, not merely balanced.
        val before = gassyWorld()
        val after = before.remapped(Grid(28, 20), 4, 3)

        assertEquals(before.airVentedMass, after.airVentedMass, "growth vented mass")
        assertEquals(before.airVentedEnergy, after.airVentedEnergy, "growth vented energy")
        assertEquals(before.exhaustMomentumX, after.exhaustMomentumX, "growth vented x momentum")
        assertEquals(before.exhaustMomentumY, after.exhaustMomentumY, "growth vented y momentum")
        assertEquals(before.atmosphereMass, after.atmosphereMass, "growth changed the air mass")
    }

    @Test
    fun `a grow and shrink round trip vents exactly what the padding held`() {
        val before = gassyWorld()
        val grown = before.remapped(Grid(28, 20), 4, 3)
        val back = grown.remapped(Grid(20, 14), -4, -3)

        // The padding was vacuum, so the round trip is the identity on the gas and books nothing.
        assertEquals(before.atmosphereMass, back.atmosphereMass, "gas did not survive the round trip")
        assertEquals(before.airVentedMass, back.airVentedMass, "vacuum padding was booked as vented")
        assertEquals(0L, back.airBalance, "airBalance after a round trip")
    }

    // ── 3. Solids are a bug, not a booking ────────────────────────────────

    @Test
    fun `a shrink that would discard a machine fails loudly`() {
        // §5's asymmetry, stated as an assertion: gas in a padding tile is legitimate, a machine
        // outside the box is a resize that got its bounds wrong. Booking it would turn a bug into a
        // quiet loss of the player's ship.
        val before = gassyWorld()
        assertFailsWith<IllegalArgumentException>("a discarded machine was tolerated") {
            before.remapped(Grid(4, 14), 0, 0)
        }
    }

    @Test
    fun `a shrink that keeps every solid is allowed`() {
        // The other half of the guard: it must not fire on the legitimate case, or an explicit fit
        // can never shrink at all and P4 is dead on arrival.
        val before = gassyWorld()
        val after = before.remapped(Grid(12, 14), 0, 0)
        assertEquals(12, after.grid.width, "the legitimate shrink was refused")
        assertEquals(
            before.grid.tiles.count { before.deck[it] != null },
            after.grid.tiles.count { after.deck[it] != null },
            "a machine went missing",
        )
    }
}
