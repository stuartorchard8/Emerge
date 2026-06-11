package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoEnergyGrid
import org.emerge.demo.cyto.sim.CytoEnergyGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Bit-identity gate for the SoA spike: the [CytoSoaReducer] over a [CytoWorld] must produce
 * byte-identical state to the engine [CytoReducer] tick for tick. Without this, any benchmark
 * number is meaningless. Settled grid colony (no division/death/reactions) is the primary
 * target and the only thing the go/no-go number depends on.
 */
class CytoSoaEquivalenceTest {

    private val cfg = CytoConfig()
    private val checkpoints = intArrayOf(1, 2, 8, 64, 250)

    @Test
    fun settledColonyIsBitIdentical() {
        for (n in intArrayOf(250, 1000, 4000)) runScenario(buildColony(n), "settled-seq-$n", null)
    }

    @Test
    fun settledColonyParallelIsBitIdentical() {
        val executor = ParallelExecutor()
        try {
            for (n in intArrayOf(1000, 4000)) runScenario(buildColony(n), "settled-par-$n", executor)
        } finally {
            executor.close()
        }
    }

    /**
     * Growing colony: Stem cells that divide on tick 1 (cooldown 0, energy 4), plus an isolated
     * low-energy cell that decays to death on tick 2. Exercises the SoA lifecycle — division
     * (daughter append + id allocation + CSR rebuild), death (tombstone + compact), and any
     * stress-breaks during settling — and asserts byte-identity vs the AoS reducer every tick.
     */
    @Test
    fun growingColonyIsBitIdentical() {
        // Sanity: the scenario must actually exercise the lifecycle, else the comparison is vacuous.
        // The doomed cell dies within a couple of ticks; the 9 Stem cells warm up (gene-driven
        // mitosis) and divide within ~60 ticks. Both the death and division structural paths run.
        run {
            val r = CytoReducer()
            val input = mapOf(PlayerId(0) to CytoInput())
            var s = buildGrowingColony()
            val before = s.components.getTable<CytoCellComponent>().asMap().size
            repeat(3) { s = r.reduce(cfg, s, input) }
            val afterDeath = s.components.getTable<CytoCellComponent>().asMap().size
            repeat(120) { s = r.reduce(cfg, s, input) }
            val grown = s.components.getTable<CytoCellComponent>().asMap().size
            assertTrue(afterDeath < before, "expected the doomed cell to die: before=$before afterDeath=$afterDeath")
            assertTrue(grown > afterDeath, "expected division growth: afterDeath=$afterDeath grown=$grown")
        }

        // Compare EVERY tick (the structural path is the most fragile — catch drift the moment
        // it appears) up to 250, both sequential and parallel.
        val dense = (1..250).toList().toIntArray()
        runScenario(buildGrowingColony(), "growing-seq", null, dense)
        val executor = ParallelExecutor()
        try {
            runScenario(buildGrowingColony(), "growing-par", executor, dense)
        } finally {
            executor.close()
        }
    }

    /**
     * Multi-species chemistry: a line of cells carrying extra chemicals that diffuse, plus
     * Contract-gene cells (Muscle/Jump). Drives the SoA biology slow path (CytoBiologyCore) and
     * asserts byte-identity vs the AoS reducer every tick — chemical maps compared per species.
     */
    @Test
    fun multiChemColonyIsBitIdentical() {
        // Sanity: extra species must actually spread to a cell that started energy-only, else the
        // multi-species path isn't really exercised.
        run {
            val r = CytoReducer()
            val input = mapOf(PlayerId(0) to CytoInput())
            var s = buildMultiChemColony()
            repeat(8) { s = r.reduce(cfg, s, input) }
            val jump = s.components.getTable<CytoCellComponent>().asMap().values.first { it.type == CellType.Jump }
            assertTrue(jump.chemicals.size > 1, "expected diffusion to spread extra species to the Jump cell: ${jump.chemicals.keys}")
        }

        val dense = (1..64).toList().toIntArray()
        runScenario(buildMultiChemColony(), "multichem-seq", null, dense)
        val executor = ParallelExecutor()
        try {
            runScenario(buildMultiChemColony(), "multichem-par", executor, dense)
        } finally {
            executor.close()
        }
    }

    /**
     * Enzyme reactions: a Touch cell seeded with touch fires its Enzyme gene on tick 1, whose
     * string-pattern reactions mint new chemical species. This is the order-sensitive path
     * (reaction float accumulation over an intents list derived from chemical-map iteration), so
     * it's the strongest check that the SoA HashMap-backed chemistry matches the AoS map.
     */
    @Test
    fun reactionColonyIsBitIdentical() {
        run {
            val r = CytoReducer()
            val input = mapOf(PlayerId(0) to CytoInput())
            var s = buildReactionColony()
            repeat(3) { s = r.reduce(cfg, s, input) }
            val touch = s.components.getTable<CytoCellComponent>().asMap().values.first { it.type == CellType.Touch }
            val minted = touch.chemicals.keys.any { it != "energy" && it != "e" && it != "n" }
            assertTrue(minted, "expected the enzyme reaction to mint a new species: ${touch.chemicals.keys}")
        }
        runScenario(buildReactionColony(), "reaction-seq", null, (1..16).toList().toIntArray())
    }

    /**
     * `CytoWorld.toSimState()` — the SoA→AoS boundary the live runtime renders/saves through —
     * must materialize a [SimState] byte-identical to what the AoS [CytoReducer] would hold, and
     * carry the engine registry (PRNG seed, allocator cursor) faithfully. Uses the growing
     * colony so division (id allocation) and death (id removal) exercise the [org.emerge.sim.core.ecs.EcsWorld]
     * round-trip, not just the cell fields. Compared via the same projection used for the AoS
     * oracle, but routed through `toSimState()` instead of `toComparison()`.
     */
    @Test
    fun exportedSimStateMatchesAos() {
        val reducer = CytoReducer()
        val soa = CytoSoaReducer(cfg, null)
        val initial = buildGrowingColony()
        val world = CytoWorld.fromSimState(initial)
        val input = mapOf(PlayerId(0) to CytoInput())

        // Pre-tick: a fresh import must export an identical projection (isolates toSimState from
        // the reducer).
        compareCells(project(initial), project(world.toSimState()), "toSimState", 0)

        var ref = initial
        for (tick in 1..250) {
            ref = reducer.reduce(cfg, ref, input)
            soa.tick(world)
            if (tick == 1 || tick == 2 || tick == 64 || tick == 250) {
                val exported = world.toSimState()
                compareCells(project(ref), project(exported), "toSimState", tick)
                compareGrid(gridColumn(ref), gridColumn(exported), "toSimState-grid", tick)
                assertEquals(ref.randomSeed, exported.randomSeed, "randomSeed tick=$tick")
                assertEquals(ref.tick, exported.tick, "tick tick=$tick")
                assertEquals(
                    ref.world.lastEntityValue, exported.world.lastEntityValue,
                    "lastEntityValue tick=$tick",
                )
            }
        }
    }

    /**
     * Pointer interaction (spawn / tap-Set / tap-Delete / multi-tick grab incl. sticky-weld /
     * detach) must stay byte-identical between the SoA reducer and the AoS [CytoReducer]. Drives
     * both from the same per-tick [CytoInput] and compares EVERY tick (the structural +
     * interaction paths are the most fragile), through both `toComparison()` and the live
     * `toSimState()` export boundary.
     */
    @Test
    fun interactionIsBitIdentical() {
        val reducer = CytoReducer()
        val soa = CytoSoaReducer(cfg, null)
        val initial = buildColony(25)            // 5×5 grid, ids 0..24; Blank/Support (no division)
        val world = CytoWorld.fromSimState(initial)
        val startCount = initial.components.getTable<CytoCellComponent>().asMap().size

        var ref = initial
        for (tick in 1..40) {
            val input = interactionInputAt(tick)
            ref = reducer.reduce(cfg, ref, mapOf(PlayerId(0) to input))
            soa.tick(world, input = input)
            compareCells(project(ref), world.toComparison().cells, "interaction", tick)
            compareCells(project(ref), project(world.toSimState()), "interaction-export", tick)
            compareGrid(gridColumn(ref), world.energyGrid.rawColumn(), "interaction", tick)
        }

        // Non-vacuous: the schedule spawns (ticks 1, 3) and deletes (tick 12), so the net cell
        // count must have moved — otherwise the comparison proves nothing.
        val endCount = ref.components.getTable<CytoCellComponent>().asMap().size
        assertTrue(endCount > startCount, "interaction should net-add cells (start=$startCount end=$endCount)")
    }

    /** Deterministic interaction schedule. The 5×5 grid spans logical (-4..4); (20+,20+) is empty. */
    private fun interactionInputAt(tick: Int): CytoInput = when (tick) {
        1 -> CytoInput(
            spawns = listOf(
                CytoInput.Spawn(20f, 20f, CellType.Blank),
                CytoInput.Spawn(24f, 20f, CellType.Stem),
            ),
        )
        3 -> CytoInput(
            taps = listOf(
                CytoInput.Tap(30f, 30f, TouchMode.Base, CellType.Blank),     // empty → spawn
                CytoInput.Tap(-4f, -4f, TouchMode.Set, CellType.Support),    // hits cell 0 → set type
            ),
        )
        in 5..10 -> CytoInput(grab = CytoInput.Grab(EntityId(0), 0f, 0f, sticky = tick % 2 == 0))
        12 -> CytoInput(taps = listOf(CytoInput.Tap(4f, 4f, TouchMode.Delete, CellType.Blank)))  // delete cell 24
        14 -> CytoInput(detaches = listOf(EntityId(12)))                      // cut centre cell's springs
        else -> CytoInput.EMPTY
    }

    private fun runScenario(
        initial: SimState,
        label: String,
        executor: ParallelExecutor?,
        checkpoints: IntArray = this.checkpoints,
    ) {
        val reducer = CytoReducer()
        val soa = CytoSoaReducer(cfg, executor)
        val world = CytoWorld.fromSimState(initial)
        val input = mapOf(PlayerId(0) to CytoInput())
        var ref = initial
        val maxTick = checkpoints.max()
        for (tick in 1..maxTick) {
            ref = reducer.reduce(cfg, ref, input)
            soa.tick(world)
            if (tick in checkpoints) compare(ref, world, label, tick)
        }
    }

    private fun compare(ref: SimState, world: CytoWorld, label: String, tick: Int) {
        compareCells(project(ref), world.toComparison().cells, label, tick)
        compareGrid(gridColumn(ref), world.energyGrid.rawColumn(), label, tick)
    }

    /** The depletable reservoir's raw column from a SimState (defaulting to a seeded grid for
     *  states that predate it, exactly as both paths do). */
    private fun gridColumn(state: SimState): LongArray =
        (state.components.getTable<CytoEnergyGridComponent>()[GRID_SINGLETON]?.grid
            ?: CytoEnergyGrid.seeded()).rawColumn()

    /** The reservoir must evolve byte-identically between the two paths (collection debits +
     *  respiration/overflow deposits are the same ops in the same cell order). */
    private fun compareGrid(ref: LongArray, soa: LongArray, label: String, tick: Int) {
        if (ref.size != soa.size) fail("$label tick=$tick: energy-grid size differs (ref ${ref.size}, soa ${soa.size})")
        for (i in ref.indices) {
            if (ref[i] != soa[i]) fail("$label tick=$tick energy-grid[$i]: ref=${ref[i]} soa=${soa[i]}")
        }
    }

    private fun compareCells(
        refCells: Map<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell>,
        soaCells: Map<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell>,
        label: String,
        tick: Int,
    ) {
        if (refCells.keys != soaCells.keys) {
            fail("$label tick=$tick: cell-id set differs (ref ${refCells.size}, soa ${soaCells.size})")
        }
        for ((id, r) in refCells) {
            val s = soaCells.getValue(id)
            fun bad(field: String, refV: Any?, soaV: Any?): Nothing =
                fail("$label tick=$tick cell=$id field=$field: ref=$refV soa=$soaV")
            if (r.posX != s.posX) bad("posX", r.posX, s.posX)
            if (r.posY != s.posY) bad("posY", r.posY, s.posY)
            if (r.ang != s.ang) bad("ang", r.ang, s.ang)
            if (r.velX != s.velX) bad("velX", r.velX, s.velX)
            if (r.velY != s.velY) bad("velY", r.velY, s.velY)
            if (r.radiusRaw != s.radiusRaw) bad("radiusRaw", r.radiusRaw, s.radiusRaw)
            compareChem("chemicals", r.chemicals, s.chemicals, ::bad)
            compareChem("pendingTransfers", r.pendingTransfers, s.pendingTransfers, ::bad)
            if (r.logicalRadius.raw != s.logicalRadius.raw) bad("logicalRadius", r.logicalRadius, s.logicalRadius)
            if (r.divideCharge.raw != s.divideCharge.raw) bad("divideCharge", r.divideCharge, s.divideCharge)
            if (r.touch.raw != s.touch.raw) bad("touch", r.touch, s.touch)
            if (r.type != s.type) bad("type", r.type, s.type)
            if (r.springs != s.springs) bad("springs", r.springs, s.springs)
            // damage compared via raw bits per entry
            if (r.damage.keys != s.damage.keys) bad("damage.keys", r.damage.keys, s.damage.keys)
            for ((nb, dv) in r.damage) {
                if (dv.toRawBits() != (s.damage[nb] ?: Float.NaN).toRawBits()) bad("damage[$nb]", dv, s.damage[nb])
            }
        }
    }

    /**
     * Compare two chemical maps by key set and per-species raw bits. Zero-valued entries are
     * dropped first: a chemical/pending value of 0 is semantically identical to absence (every
     * consumer reads `map[k] ?: 0f`), and the two storages disagree on whether to keep the key —
     * the SoA energy column is always present (so a freshly-divided daughter reads `{energy:0}`)
     * while the AoS map has no key. Filtering ±0 normalizes that without masking any nonzero
     * difference (a real value mismatch survives the filter and is caught).
     */
    private fun compareChem(
        field: String,
        ref: Map<String, Frac>,
        soa: Map<String, Frac>,
        bad: (String, Any?, Any?) -> Unit,
    ) {
        val r = ref.filterValues { it.raw != 0L }
        val s = soa.filterValues { it.raw != 0L }
        if (r.keys != s.keys) bad("$field.keys", r.keys, s.keys)
        for ((k, v) in r) {
            val sv = s[k]
            if (sv == null || v.raw != sv.raw) bad("$field[$k]", v, s[k])
        }
    }

    /** Project an engine SimState to the same comparison form CytoWorld.toComparison() yields. */
    private fun project(state: SimState): Map<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell> {
        val cells = state.components.getTable<CytoCellComponent>().asMap()
        val transforms = state.components.getTable<TransformComponent>()
        val motions = state.components.getTable<MotionComponent>()
        val colliders = state.components.getTable<ColliderComponent>()
        val springs = state.components.getTable<SpringConstraintComponent>().asMap()
        val damages = state.components.getTable<ConnectionStateComponent>().asMap()
        val out = HashMap<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell>(cells.size * 2)
        for ((id, cell) in cells) {
            val t = transforms[id]!!
            val m = motions[id]!!
            val springMap = HashMap<Int, org.emerge.demo.cyto.sim.soa.SpringTriple>()
            val damageMap = HashMap<Int, Float>()
            springs[id]?.springs?.forEach { sp ->
                springMap[sp.other.value] = org.emerge.demo.cyto.sim.soa.SpringTriple(sp.restLength.raw, sp.stiffness.raw, sp.damping.raw)
            }
            damages[id]?.damage?.forEach { (nb, dv) -> damageMap[nb.value] = dv }
            out[id.value] = org.emerge.demo.cyto.sim.soa.ComparisonCell(
                posX = t.pos.x.raw, posY = t.pos.y.raw, ang = t.ang.raw,
                velX = m.vel.x.raw, velY = m.vel.y.raw,
                radiusRaw = colliders[id]!!.radius.raw,
                chemicals = cell.chemicals, pendingTransfers = cell.pendingTransfers,
                logicalRadius = cell.logicalRadius, divideCharge = cell.divideCharge,
                touch = cell.touch, type = cell.type.ordinal,
                sticky = cell.sticky, stickyTemp = cell.stickyTemp,
                springs = springMap, damage = damageMap,
            )
        }
        return out
    }

    /** Same settled mesh as CytoPerfBenchmark.buildColony: ~1-in-20 Support, rest Blank. */
    private fun buildColony(targetCells: Int): SimState {
        val builder = SimBuilder(SimState())
        val side = ceil(sqrt(targetCells.toDouble())).toInt()
        val spacing = 2.0f
        val grid = arrayOfNulls<EntityId>(side * side)
        var placed = 0
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (placed >= targetCells) break
                val x = (col - side / 2) * spacing
                val y = (row - side / 2) * spacing
                val support = placed % 20 == 0
                grid[row * side + col] = builder.spawnCell(
                    pos = CytoUnits.coord2(x, y), vel = Coord2.zero,
                    type = if (support) CellType.Support else CellType.Blank,
                    chemicals = mapOf("energy" to Frac(4, 5)), logicalRadius = Frac(1, 1),
                )
                placed++
            }
        }
        for (row in 0 until side) {
            for (col in 0 until side) {
                val id = grid[row * side + col] ?: continue
                if (col + 1 < side) grid[row * side + col + 1]?.let { addSpring(builder, id, it, cfg) }
                if (row + 1 < side) grid[(row + 1) * side + col]?.let { addSpring(builder, id, it, cfg) }
            }
        }
        return builder.build()
    }

    /** A 3×3 mesh of Stem cells primed to divide on tick 1, plus one doomed isolated cell. */
    private fun buildGrowingColony(): SimState {
        val builder = SimBuilder(SimState())
        val side = 3
        val spacing = 2.0f
        val grid = arrayOfNulls<EntityId>(side * side)
        for (row in 0 until side) {
            for (col in 0 until side) {
                val x = (col - side / 2) * spacing
                val y = (row - side / 2) * spacing
                val id = builder.spawnCell(
                    pos = CytoUnits.coord2(x, y), vel = Coord2.zero,
                    // Energy above the Stem mitosis gate (0.5) so the gene-driven warm-up reaches the
                    // divide threshold within the comparison window (≈ DIVIDE_THRESHOLD / (energy−gate) ticks).
                    type = CellType.Stem, chemicals = mapOf("energy" to Frac(9, 10)), logicalRadius = Frac(1, 1),
                )
                builder.update<CytoCellComponent>(id) { (it!!).copy(divideCharge = Frac(0, 1)) }
                grid[row * side + col] = id
            }
        }
        for (row in 0 until side) {
            for (col in 0 until side) {
                val id = grid[row * side + col]!!
                if (col + 1 < side) addSpring(builder, id, grid[row * side + col + 1]!!, cfg)
                if (row + 1 < side) addSpring(builder, id, grid[(row + 1) * side + col]!!, cfg)
            }
        }
        // An isolated low-energy cell whose decay drives it to 0 energy → death on tick 2.
        builder.spawnCell(
            pos = CytoUnits.coord2(50f, 50f), vel = Coord2.zero,
            type = CellType.Blank, chemicals = mapOf("energy" to Frac(1, 100000)), logicalRadius = Frac(1, 1),
        )
        return builder.build()
    }

    /** A connected line carrying extra chemicals (x, e) plus Contract-gene cells. */
    private fun buildMultiChemColony(): SimState {
        val builder = SimBuilder(SimState())
        val a = builder.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to Frac(2, 5), "x" to Frac(3, 10)), Frac(1, 1))
        val b = builder.spawnCell(CytoUnits.coord2(2f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to Frac(2, 5), "x" to Frac(3, 10)), Frac(1, 1))
        val c = builder.spawnCell(CytoUnits.coord2(4f, 0f), Coord2.zero, CellType.Muscle, mapOf("energy" to Frac(3, 10), "e" to Frac(1, 5)), Frac(1, 1))
        val d = builder.spawnCell(CytoUnits.coord2(6f, 0f), Coord2.zero, CellType.Jump, mapOf("energy" to Frac(2, 5)), Frac(1, 1))
        addSpring(builder, a, b, cfg)
        addSpring(builder, b, c, cfg)
        addSpring(builder, c, d, cfg)
        return builder.build()
    }

    /** A Touch cell seeded with touch + reactant chemicals; its enzyme reaction mints species. */
    private fun buildReactionColony(): SimState {
        val builder = SimBuilder(SimState())
        val t = builder.spawnCell(
            CytoUnits.coord2(0f, 0f), Coord2.zero,
            CellType.Touch, mapOf("energy" to Frac(1, 5), "e" to Frac(1, 5), "n" to Frac(1, 5)), Frac(1, 1),
        )
        builder.update<CytoCellComponent>(t) { (it!!).copy(touch = Frac(1, 1)) }
        return builder.build()
    }
}
