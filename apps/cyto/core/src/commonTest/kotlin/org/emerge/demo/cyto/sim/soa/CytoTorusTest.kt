package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The torus is homogeneous: **the boundary is no different from the centre.** Cyto wraps via `Coord`'s
 * two's-complement Int overflow (boundary = ±Int.MAX = logical ±[CytoUnits.CELLS_PER_AXIS]); the
 * `SpatialGrid` broadphase + the light/matter fields all tile that same period. So a scene shifted by an
 * exact raw offset onto the seam must evolve **bit-identically** (in relative terms) to the same scene at
 * the centre — the constant offset cancels in `Coord`-delta subtraction.
 *
 * NB the shift is applied at the **raw Coord level** (`posX[i] += offsetRaw`, which wraps), NOT by spawning
 * at `coord(logical+offset)` — the latter rounds in `double` per cell, so the two scenes wouldn't even start
 * from bit-identical relative geometry. This test caught a real bug: the weld solver widened positions to
 * `Long` and subtracted non-modularly, so a weld straddling the seam saw a ~2³² gap and exploded (fixed in
 * `CytoSoaReducer.springSolve` — Int-modular deltas).
 */
class CytoTorusTest {

    private val cfg = CytoConfig(mutationRateDenom = 0)
    private val aId = SpeciesRegistry.id("r")
    private val abId = SpeciesRegistry.id("rg")

    /** Relative state of a world: each cell's torus-delta from the lowest-id cell + vel/radius/chem, sorted
     *  by EntityId. The delta uses raw `Int` subtraction (modular on the torus), so a whole-world translation
     *  cancels — two scenes differing only by a constant offset hash identically iff their dynamics match. */
    private fun relDigest(w: CytoWorld): String {
        val order = (0 until w.count).sortedBy { w.entityId[it] }
        if (order.isEmpty()) return "EMPTY"
        val ref = order[0]; val rx = w.posX[ref]; val ry = w.posY[ref]
        val sb = StringBuilder()
        for (i in order) {
            sb.append(w.posX[i] - rx).append(',').append(w.posY[i] - ry).append(',')   // Int = modular torus delta
                .append(w.velX[i]).append(',').append(w.velY[i]).append(',')
                .append(w.cell.logicalRadius[i]).append(',')
                .append(w.cell.cytoplasm[i]?.count(aId) ?: 0).append(',')
                .append(w.cell.biomass[i]?.count(abId) ?: 0).append(';')
        }
        return sb.toString()
    }

    /** A welded 5-cell star (centre kicked so springs/drag/contacts all fire) on uniform matter, shifted by
     *  [offsetRaw] at the raw Coord level so the geometry is bit-identical but lands on the seam, run [ticks]. */
    private fun runCluster(offsetRaw: Int, ticks: Int, genome: List<Gene>): CytoWorld {
        val pts = listOf(0f to 0f, 1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
        val initial: SimState = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            val ids = pts.mapIndexed { k, p ->
                b.spawnCell(pos = CytoUnits.coord2(p.first, p.second),
                    vel = if (k == 0) CytoUnits.coord2(0.03f, 0.02f) else Coord2.zero, type = CellType.Collector,
                    cytoplasm = mapOf("r" to 2000, "rg" to 40000), biomass = CytoSeed.STARTER_BIOMASS,
                    logicalRadius = MIN_RADIUS, genome = genome)
            }
            for (k in 1 until ids.size) addSpring(b, ids[0], ids[k], cfg)
            val grid = CytoMatterField.seededUniform(4000)   // uniform matter → no position dependence
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
            b.build()
        }
        val w = CytoWorld.fromSimState(initial)
        if (offsetRaw != 0) for (i in 0 until w.count) { w.posX[i] += offsetRaw; w.posY[i] += offsetRaw }  // wraps
        val soa = CytoSoaReducer(cfg)
        var cur = w
        repeat(ticks) { cur = soa.tick(cur, CytoInput.EMPTY) }
        return cur
    }

    @Test
    fun boundaryEqualsCentreForPhysics() {
        // springs + drag + contacts + integrate-wrap + broadphase-wrap, no genes.
        val empty = emptyList<Gene>()
        assertEquals(relDigest(runCluster(0, 400, empty)), relDigest(runCluster(Int.MAX_VALUE, 400, empty)),
            "physics at the seam must match the centre (torus is homogeneous)")
    }

    @Test
    fun boundaryEqualsCentreWithContractionBiology() {
        // Adds the actuator + biology: Break-powered (NO Light → position-independent) contraction on uniform
        // matter exercises radius actuation → spring/drag coupling across the seam.
        val contract = GeneCodec.parse("Break ab : Biomass > 0 : Contract @15")
        assertEquals(relDigest(runCluster(0, 400, contract)), relDigest(runCluster(Int.MAX_VALUE, 400, contract)),
            "contraction+biology at the seam must match the centre")
    }

    @Test
    fun lightFieldIsToroidallyPeriodic() {
        // Light wraps via wrapDelta's Float add/subtract, so SPAN-periodicity holds only up to Float ULP.
        // The error scales with the band's steepness: sampleAt is a gaussian of half-width LIGHT_FALLOFF,
        // so a narrow day band (small dayFraction) amplifies the same input ULP into a bigger output
        // difference. A fixed raw tolerance therefore goes stale whenever the day/night geometry changes.
        //
        // Express it in the unit that actually matters instead. Light is consumed as INTEGER quanta
        // (× LIGHT_QUANTA_SCALE, ÷ Int.MAX), so one quantum is `Frac.ONE / LIGHT_QUANTA_SCALE` raw and
        // anything below that is invisible to the sim. Allow a thousandth of a quantum: ~1000x above the
        // observed float noise (worst ≈ 1.8k raw over a dense sweep of positions and ticks), and still
        // ~1000x below a difference that could change any cell's energy by even one op. A genuine
        // periodicity break would be on the order of the light value itself — millions of raw — so this
        // stays a real assertion, not a rubber stamp.
        val field = CytoLightField.default()
        val span = CytoLightField.SPAN
        val tol = (Int.MAX_VALUE.toLong() / CytoTuning.LIGHT_QUANTA_SCALE) / 1000
        for (t in listOf(0L, 137L, 9001L)) {
            for (x in listOf(-100f, -1f, 0f, 1f, 63f, 100f)) {
                for (y in listOf(-50f, 0f, 77f)) {
                    val d = field.sampleAt(x, y, t).raw - field.sampleAt(x + span, y + span, t).raw
                    assertTrue(d in -tol..tol, "light must be ~SPAN-periodic at ($x,$y,t=$t); off by $d")
                }
            }
        }
    }

    @Test
    fun matterFieldIsToroidallyPeriodic() {
        // The field tiles the torus: contents read at (x,y) must equal those at (x+SPAN, y+SPAN).
        val grid = CytoMatterField.seededUniform(10)
        val span = CytoLightField.SPAN
        for (x in listOf(-130f, -64f, -1f, 0f, 1f, 64f, 130f)) {
            for (y in listOf(-77f, 0f, 64f)) {
                assertEquals(grid.contentsAt(x, y), grid.contentsAt(x + span, y + span),
                    "matter field must be SPAN-periodic at ($x,$y)")
            }
        }
    }
}
