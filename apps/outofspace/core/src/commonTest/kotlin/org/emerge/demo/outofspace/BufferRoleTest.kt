package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.bufferRolesOf
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.portsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [bufferTile] restates the port geometry rather than calling [portsOf], so that a per-machine walk
 * allocates nothing. These hold the two in agreement — the drift they guard against is silent, since
 * a store one tile off its port still works, still conserves, and simply is not where the machine's
 * mouth is.
 */
class BufferRoleTest {

    private val grid = Grid(21, 21)
    private val centre get() = grid.tile(10, 10)

    private fun kinds(facing: Direction): List<Machine> = listOf(
        Extractor(facing), Vaporizer(facing), Thruster(facing),
        Processor(facing), Smelter(facing), ThermalDecomposer(facing), Storage(facing),
    )

    @Test
    fun `an input store stands on the input port, in every orientation`() {
        for (facing in Direction.ALL) for (m in kinds(facing)) {
            val tile = bufferTile(grid, m, centre, BufferRole.Input) ?: continue
            val port = portsOf(grid, m, centre).firstOrNull { it.kind == PortKind.Input }
            assertNotNull(port, "$m has an input store but no input port")
            assertEquals(port.tile, tile, "$m facing $facing")
        }
    }

    @Test
    fun `product and waste stores stand on the ports they feed`() {
        for (facing in Direction.ALL) for (m in kinds(facing)) {
            // Storage is the exception on purpose: one pooled store, serving both of its ports.
            if (m is Storage) continue
            val ports = portsOf(grid, m, centre).filter { it.kind == PortKind.Output }
            for ((role, stream) in listOf(BufferRole.Product to Stream.Product, BufferRole.Waste to Stream.Waste)) {
                val tile = bufferTile(grid, m, centre, role) ?: continue
                val port = ports.firstOrNull { it.stream == stream }
                assertNotNull(port, "$m has a $role store but no $stream port")
                assertEquals(port.tile, tile, "$m facing $facing")
            }
        }
    }

    @Test
    fun `one machine never puts two stores on one tile`() {
        for (facing in Direction.ALL) for (m in kinds(facing)) {
            val tiles = bufferRolesOf(m).map { bufferTile(grid, m, centre, it) }
            assertEquals(tiles.size, tiles.toSet().size, "$m facing $facing collides: $tiles")
        }
    }

    @Test
    fun `every store lies inside its own machine's footprint`() {
        for (facing in Direction.ALL) for (m in kinds(facing)) {
            val r = m.kind.reach
            for (role in bufferRolesOf(m)) {
                val tile = bufferTile(grid, m, centre, role)!!
                val dx = grid.xOf(tile) - grid.xOf(centre)
                val dy = grid.yOf(tile) - grid.yOf(centre)
                assertTrue(dx in -r..r && dy in -r..r, "$m $role sits at ($dx, $dy), outside reach $r")
            }
        }
    }

    @Test
    fun `machines that hold nothing claim nothing`() {
        for (m in listOf(Sensor(Direction.Right), Pump(Direction.Right))) {
            assertEquals(emptyList(), bufferRolesOf(m), "$m claims a store")
            for (role in BufferRole.entries) assertNull(bufferTile(grid, m, centre, role))
        }
    }
}
