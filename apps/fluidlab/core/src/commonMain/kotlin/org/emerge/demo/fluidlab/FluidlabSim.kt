package org.emerge.demo.fluidlab

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.Hull
import org.emerge.demo.fluidlab.world.Machine
import org.emerge.demo.fluidlab.world.StructureMap
import org.emerge.demo.fluidlab.world.Temperature
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.gasCapacityAt
import org.emerge.demo.fluidlab.world.fluid.stepFluid
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.ecs.PipelineProfiler

/**
 * **Fluidlab** — the momentum-solving fluid simulation, extracted from Out of Space so it stays
 * runnable after that game stopped using it. See `apps/outofspace/PLAN_fluid_extraction.md`.
 *
 * Out of Space wanted an atmosphere *inside a vessel*, so its tick had to interleave the solver with
 * machines, rails, signals, heat conduction and flight. None of that is here. This is the solver and
 * a box to run it in: a grid, walls, air, and [stepFluid]. What was a supporting subsystem is now the
 * whole program, which is the point — it can be driven, broken and read directly rather than through
 * a game that happens to contain it.
 *
 * The reducer contract still holds ([SimReducer]: `reduce` is pure, no wall clock, no platform RNG),
 * because that is what buys replay, headless tests and identical behaviour across the three hosts.
 */
data class FluidlabConfig(
    val width: Int = 32,
    val height: Int = 24,
    /**
     * Which way is down, and how hard. `+y` is screen-down — the solver's convention, see [EdgeGrid].
     * [FREEFALL] turns gravity off entirely, which is the honest way to watch pressure alone: with no
     * body force there is no hydrostatic gradient, so anything that still moves, moves because of a
     * pressure difference.
     */
    val gravity: Frac2 = DOWN,
    /**
     * Whether boiling and condensing cost and release energy.
     *
     * Off by default, matching [stepFluid], and off means a tick is bit-identical to one simulated
     * before latent heat existed. On, the mass and energy ledgers stop balancing — not from a bug but
     * because the energy moves into a reservoir nothing here counts yet. [FluidStepReport.cohesionUnpaid]
     * is the part the tick could not charge for.
     */
    val latentHeat: Boolean = false,
    /** Fixed timestep. The sim advances in whole ticks, never by frame delta. */
    val secondsPerTick: Float = 1f / 60f,
) {
    companion object {
        val DOWN = Frac2(Frac(0L, 1), Frac(1L, 1))
        val FREEFALL = Frac2(Frac(0L, 1), Frac(0L, 1))
    }
}

/** What one tick of the solver did, kept on the state so the harness and HUD can read it. */
data class FluidStepReport(
    val ventedGrams: Long = 0L,
    val ventedJoules: Long = 0L,
    /** Impulse on the hull — the reaction to air pressing on walls it cannot get through. */
    val vesselX: Long = 0L,
    val vesselY: Long = 0L,
    /** Thrust against the expelled gas, which is not the same thing as [vesselX] — see `FluidStep`. */
    val escapedX: Long = 0L,
    val escapedY: Long = 0L,
    /** Impulse the pressure solve had nowhere to put. Size measures discretisation error in thrust. */
    val undeliveredX: Long = 0L,
    val undeliveredY: Long = 0L,
    /** 1 = gas moves under a tile per tick. Climbing toward MAX = the explicit solver is being outrun. */
    val subSteps: Int = 0,
    /** Latent heat the tick could not charge for. Physically should always be zero — see [FluidlabConfig]. */
    val cohesionUnpaid: Long = 0L,
)

/** One edit to the world, applied at the top of a tick. Values, not callbacks, so replays work. */
sealed interface FluidlabEdit {
    /** Put a wall at [tile], or take one away. */
    data class SetWall(val tile: Int, val present: Boolean) : FluidlabEdit

    /** Add [mass] of [species] at [tile], carrying enough energy to sit at [kelvin]. */
    data class Inject(val tile: Int, val species: Species, val mass: Long, val kelvin: Int) : FluidlabEdit

    /** Take all the air out of [tile] — an instant local vacuum. */
    data class Evacuate(val tile: Int) : FluidlabEdit

    /** Add [energy] to [tile]'s air without adding any mass. Negative cools it. */
    data class Heat(val tile: Int, val energy: Long) : FluidlabEdit
}

data class FluidlabInput(
    val edits: List<FluidlabEdit> = emptyList(),
) : SimInput {
    companion object {
        val EMPTY = FluidlabInput()
    }
}

/**
 * The whole world at one instant.
 *
 * [walls] is a `List<Machine?>` of [Hull] or null rather than a `BooleanArray` because [StructureMap]
 * derives enclosure from machines, and reusing that is what keeps this the *same* solver Out of Space
 * ran rather than a lookalike. A lab that quietly rebuilt its own structure model would stop being
 * evidence about the original.
 */
data class FluidlabState(
    val grid: Grid,
    val walls: List<Machine?>,
    val air: AirField,
    val momentumX: LongArray,
    val momentumY: LongArray,
    val tick: Long,
    val report: FluidStepReport = FluidStepReport(),
    /** Running totals, so a long run can be checked for conservation without watching every tick. */
    val totalVentedGrams: Long = 0L,
    val totalVentedJoules: Long = 0L,
) {
    /** Total gas mass in the world. With no venting this must not change — the first thing to check. */
    fun totalGrams(): Long {
        val mass = air.copyGrams()
        var sum = 0L
        for (g in mass) sum += g
        return sum
    }

    fun totalJoules(): Long {
        val energy = air.copyJoules()
        var sum = 0L
        for (j in energy) sum += j
        return sum
    }

    /** Structure derived from the walls — what the solver treats as solid, enclosed, or vacuum. */
    fun structure(): StructureMap = StructureMap.derive(grid, walls)

    // Arrays in a data class: equals/hashCode would compare by identity and lie. Nothing depends on
    // structural equality of a world, so the honest move is to not offer it.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = tick.hashCode()

    companion object {
        /**
         * A sealed box of ordinary air with a one-tile wall border, which is the smallest world in
         * which anything interesting can be asked: it holds pressure, so a breach means something.
         */
        fun sealedRoom(cfg: FluidlabConfig = FluidlabConfig()): FluidlabState {
            val grid = Grid(cfg.width, cfg.height)
            val walls = MutableList<Machine?>(grid.size) { null }
            for (x in 0 until grid.width) {
                walls[grid.index(x, 0)] = Hull()
                walls[grid.index(x, grid.height - 1)] = Hull()
            }
            for (y in 0 until grid.height) {
                walls[grid.index(0, y)] = Hull()
                walls[grid.index(grid.width - 1, y)] = Hull()
            }
            val structure = StructureMap.derive(grid, walls)
            val edges = EdgeGrid(grid)
            return FluidlabState(
                grid = grid,
                walls = walls,
                air = AirField.ambient(grid, structure),
                momentumX = LongArray(edges.xEdgeCount),
                momentumY = LongArray(edges.yEdgeCount),
                tick = 0,
            )
        }

        /** An empty grid: no walls, no air. Everything vents to the rim. */
        fun vacuum(cfg: FluidlabConfig = FluidlabConfig()): FluidlabState {
            val grid = Grid(cfg.width, cfg.height)
            val edges = EdgeGrid(grid)
            return FluidlabState(
                grid = grid,
                walls = MutableList(grid.size) { null },
                air = AirField.of(LongArray(grid.size * Species.COUNT)),
                momentumX = LongArray(edges.xEdgeCount),
                momentumY = LongArray(edges.yEdgeCount),
                tick = 0,
            )
        }
    }
}

object FluidlabReducer : SimReducer<FluidlabConfig, FluidlabState, FluidlabInput> {

    override fun reduce(
        cfg: FluidlabConfig,
        state: FluidlabState,
        inputs: Map<PlayerId, FluidlabInput>,
        profiler: PipelineProfiler?,
    ): FluidlabState {
        val grid = state.grid
        val mass = state.air.copyGrams()
        val energy = state.air.copyJoules()
        val mx = state.momentumX.copyOf()
        val my = state.momentumY.copyOf()
        var walls = state.walls

        // Sorted by PlayerId, not map order: two peers must apply the same edits in the same order.
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) {
                when (edit) {
                    is FluidlabEdit.SetWall -> {
                        if (edit.tile !in 0 until grid.size) continue
                        val next = walls.toMutableList()
                        next[edit.tile] = if (edit.present) Hull() else null
                        walls = next
                    }

                    is FluidlabEdit.Inject -> {
                        if (edit.tile !in 0 until grid.size || edit.mass <= 0L) continue
                        mass[edit.tile * Species.COUNT + edit.species.ordinal] += edit.mass
                        // Energy derived from the mass so the gas arrives at the stated temperature
                        // rather than at whatever the tile's existing energy imply. Capacity is read
                        // *after* the mass lands, which is what makes the two agree.
                        energy[edit.tile] = gasCapacityAt(mass, edit.tile) * edit.kelvin
                    }

                    is FluidlabEdit.Evacuate -> {
                        if (edit.tile !in 0 until grid.size) continue
                        val base = edit.tile * Species.COUNT
                        for (s in Species.ALL) mass[base + s.ordinal] = 0L
                        energy[edit.tile] = 0L
                    }

                    is FluidlabEdit.Heat -> {
                        if (edit.tile !in 0 until grid.size) continue
                        energy[edit.tile] = (energy[edit.tile] + edit.energy).coerceAtLeast(0L)
                    }
                }
            }
        }

        val edges = EdgeGrid(grid)
        val structure = StructureMap.derive(grid, walls)
        val step = stepFluid(
            edges = edges,
            apertures = ApertureField.derive(edges, structure),
            mass = mass,
            mx = mx,
            my = my,
            gravity = cfg.gravity,
            gasJoules = energy,
            volumes = null,
            latentHeat = cfg.latentHeat,
        )

        return state.copy(
            walls = walls,
            air = step.air,
            momentumX = step.momentumX,
            momentumY = step.momentumY,
            tick = state.tick + 1,
            report = FluidStepReport(
                ventedGrams = step.ventedGrams,
                ventedJoules = step.ventedJoules,
                vesselX = step.vesselX,
                vesselY = step.vesselY,
                escapedX = step.escapedX,
                escapedY = step.escapedY,
                undeliveredX = step.undeliveredX,
                undeliveredY = step.undeliveredY,
                subSteps = step.subSteps,
                cohesionUnpaid = step.cohesionUnpaid,
            ),
            totalVentedGrams = state.totalVentedGrams + step.ventedGrams,
            totalVentedJoules = state.totalVentedJoules + step.ventedJoules,
        )
    }

    override fun patchState(state: FluidlabState, delta: FluidlabState): FluidlabState = delta
}

/** Ambient air temperature, re-exported so hosts and scripts need not reach into `world`. */
val AMBIENT_KELVIN: Int get() = Temperature.AMBIENT_KELVIN
