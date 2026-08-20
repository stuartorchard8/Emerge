package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
import org.emerge.demo.outofspace.chem.REDUCTIONS
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two dials, as the player actually reaches them.
 *
 * `ThermalDecomposerTest` proves the machine *obeys* a setpoint and a dwell; this proves they can be
 * **set**, which is a separate claim and was false for the setpoint's entire life — it existed, it
 * was saved, and there was no way to change it except by editing a save file. A dial nobody can turn
 * is the same as no dial, and it fails silently: the machine works perfectly at whatever it was
 * built with.
 */
class ThermalDecomposerUiTest {

    private val grid = Grid(9, 9)
    private val cfg = OutofspaceConfig(initialGrid = grid)
    private val centre = grid.tile(4, 4)

    private fun world(): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        deck += ThermalDecomposer(centre, Direction.Right)
        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            creative = true,
        )
    }

    private fun controller() = OutofspaceController(cfg, world())

    private fun machine(c: OutofspaceController) = c.state.deck[centre] as ThermalDecomposer

    /** Push whatever the controller has queued through the reducer, the way a frame does. */
    private fun OutofspaceController.settle() = repeat(2) { stepOnce() }

    // ── The ladders reach everything ─────────────────────────────────────────

    @Test
    fun `every reaction in the game has a setpoint hot enough to run it`() {
        // ⛔ The guard on the ladder. A reaction added with an onset above the top rung would be
        // unreachable through the UI and perfectly fine in every other test — the table would balance,
        // the sweep would run it, and no player could ever get there. Strictly above, not at: a
        // reaction *at* its onset runs at the base rate and essentially nothing happens.
        val hottest = ThermalDecomposer.SETPOINTS.max()

        for (reaction in DECOMPOSITIONS) {
            assertTrue(
                hottest > reaction.onsetKelvin,
                "${reaction.reactant} needs ${reaction.onsetKelvin} K and the dial stops at $hottest K",
            )
        }
        for (reaction in REDUCTIONS) {
            assertTrue(
                hottest > reaction.onsetKelvin,
                "${reaction.oxide} + ${reaction.reductant} needs ${reaction.onsetKelvin} K and the dial " +
                    "stops at $hottest K",
            )
        }
    }

    @Test
    fun `the coldest setpoint runs nothing at all`() {
        // The off switch. It is only an off switch if it is below every onset in the game, and that
        // is a fact about the tables rather than about the number 300.
        val coldest = ThermalDecomposer.SETPOINTS.min()
        val lowestOnset = minOf(
            DECOMPOSITIONS.minOf { it.onsetKelvin },
            REDUCTIONS.minOf { it.onsetKelvin },
        )
        assertTrue(coldest < lowestOnset, "the coldest setpoint $coldest K still runs something")
    }

    @Test
    fun `the default dwell is the first rung, so the dial starts where the machine does`() {
        // Otherwise the first tap moves the setting to somewhere it already was, or skips a rung —
        // both of which read as the control being broken.
        assertEquals(ThermalDecomposer.DWELLS.first(), ThermalDecomposer(centre, Direction.Right).dwellTicks)
    }

    // ── Turning them ─────────────────────────────────────────────────────────

    @Test
    fun `tapping the temperature raises it`() {
        val c = controller()
        val before = machine(c).setTemperature
        c.cycleDecomposerTemperature(centre, 1)
        c.settle()

        val after = machine(c).setTemperature
        assertTrue(after != before, "the setpoint did not move")
        assertTrue(after in ThermalDecomposer.SETPOINTS, "$after is not a rung on the ladder")
    }

    @Test
    fun `tapping the dwell raises it`() {
        val c = controller()
        c.cycleDecomposerDwell(centre, 1)
        c.settle()

        assertEquals(ThermalDecomposer.DWELLS[1], machine(c).dwellTicks, "the dwell did not step to the next rung")
    }

    @Test
    fun `both dials wrap all the way round`() {
        val c = controller()
        val startTemp = machine(c).setTemperature
        repeat(ThermalDecomposer.SETPOINTS.size) {
            c.cycleDecomposerTemperature(centre, 1)
            c.settle()
        }
        assertEquals(startTemp, machine(c).setTemperature, "a full lap of the temperature dial did not come home")

        val startDwell = machine(c).dwellTicks
        repeat(ThermalDecomposer.DWELLS.size) {
            c.cycleDecomposerDwell(centre, 1)
            c.settle()
        }
        assertEquals(startDwell, machine(c).dwellTicks, "a full lap of the dwell dial did not come home")
    }

    @Test
    fun `moving one dial leaves the other alone`() {
        // The edit carries both values, so this is exactly the mistake it could make: reading a stale
        // copy of the dial the player did not touch and writing it back over a fresh one.
        val c = controller()
        c.cycleDecomposerDwell(centre, 1)
        c.settle()
        val dwell = machine(c).dwellTicks

        c.cycleDecomposerTemperature(centre, 1)
        c.settle()

        assertEquals(dwell, machine(c).dwellTicks, "changing the temperature reset the dwell")
    }

    @Test
    fun `a setpoint from an older save steps to the next rung rather than to the coldest`() {
        // A save written before the ladder existed holds 900, and one edited by hand holds anything.
        // `indexOf` misses both, and the tempting fallback — start at index 0 — makes the first tap on
        // a 2000 K furnace turn it off.
        val deck = DeckArray(grid)
        deck += ThermalDecomposer(centre, Direction.Right, setTemperature = 1300)
        val c = OutofspaceController(cfg, world().copy(deck = deck))

        c.cycleDecomposerTemperature(centre, 1)
        c.settle()

        val after = (c.state.deck[centre] as ThermalDecomposer).setTemperature
        assertTrue(
            after > 1300,
            "an off-ladder setpoint of 1300 K stepped to $after — it should climb to the next rung",
        )
    }

    @Test
    fun `retuning restarts the dwell`() {
        // A charge part-way through a hold the player has just changed has not served the new time,
        // and carrying its progress across would make the first charge after every adjustment come
        // out to a setting nobody chose.
        val deck = DeckArray(grid)
        deck += ThermalDecomposer(centre, Direction.Right, dwellTicks = 500, heldTicks = 300)
        val c = OutofspaceController(cfg, world().copy(deck = deck))

        c.cycleDecomposerDwell(centre, 1)
        c.settle()

        assertEquals(0, (c.state.deck[centre] as ThermalDecomposer).heldTicks, "the part-served hold carried over")
    }

    @Test
    fun `a machine can be selected without the wire tool out`() {
        // ⛔ **The bug this found.** Selection used to happen only under `Tool.Wire`, which was fine
        // while wiring was the only reason to select anything. Every machine panel since then stands
        // down while the wire tool is out — as it must, or two panels fight over one corner — so a
        // panel could exist, be saved, be tested, and be *unreachable*. The storage lock shipped that
        // way, and these dials would have too.
        //
        // Asserted for the build tool specifically because that is the tool a player is holding when
        // they have just placed the machine they want to tune.
        for (tool in listOf(Tool.Build, Tool.Delete, Tool.Cancel)) {
            val c = controller()
            c.tool = tool
            c.select(centre)
            assertEquals(centre, c.selected, "a machine could not be selected with the $tool tool out")
        }
    }

    @Test
    fun `clicking bare deck selects nothing, so a click away dismisses the panel`() {
        val c = controller()
        c.select(centre)
        assertEquals(centre, c.selected, "the fixture never selected anything")

        c.select(grid.tile(2, 2))
        assertEquals(TileIndex.NONE, c.selected, "empty deck left the previous machine selected")
    }

    @Test
    fun `the dials can be reached from any tile the machine covers`() {
        // It is a three-by-three, and the player clicks where they click. The panel reads through
        // `machineCovering` and the edit resolves through `originAt`; either one taking the tile
        // literally would make the dials work only from the middle square.
        val corner = grid.tile(grid.xOf(centre) + 1, grid.yOf(centre) + 1)
        val c = controller()
        val before = machine(c).setTemperature

        c.cycleDecomposerTemperature(corner, 1)
        c.settle()

        assertTrue(machine(c).setTemperature != before, "the dial could not be reached from an edge tile")
    }
}
