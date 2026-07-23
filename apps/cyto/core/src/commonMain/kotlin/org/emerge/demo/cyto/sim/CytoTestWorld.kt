package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * **Construct a specific world state directly, instead of growing one and hoping.**
 *
 * Most of the time lost verifying cyto behaviour goes on *hunting for conditions*, not on testing them: a
 * cell in daylight; a cell that can just afford to divide; a gene that flickers. Each hunt is a run of the
 * sim with a guess at how long to wait, and some conditions can't be reached that way at all — the strict
 * 1/n division split ended up pinned by a unit test on the arithmetic because no live world could be
 * steered into the window that discriminates it.
 *
 * This states the world instead:
 *
 * ```
 * CytoTestWorld.empty()
 *     .cell("a", biomass = mapOf("rg" to 3000), cytoplasm = mapOf("g" to 900), genome = genes, light = Light.None)
 *     .matter(level = 0)
 *     .build()
 * ```
 *
 * It lives in commonMain rather than a test source set on purpose: `commonTest` and the desktop agent
 * harness are different Gradle modules, and both need it — a fixture you can assert on but not *look* at is
 * half a tool. See [CytoFixtures] for the named states both share.
 *
 * **Light is a position, not a field.** [CytoLightField] is a pure function of x and tick — a daylight band
 * sweeping the torus — so there is nothing to set. Asking for [Light.Full] or [Light.None] instead *places*
 * the cell where that is true at tick 0, and cells that would collide on the same x are spread along y
 * (which light ignores). Give an explicit [at] when position matters more than illumination.
 */
class CytoTestWorld private constructor(private val scenario: CytoScenario) {

    /** How much of the daylight band a cell should be standing in. Resolved to an x position at tick 0. */
    sealed interface Light {
        /** Directly under the band's centre — peak illumination. */
        data object Full : Light
        /** Half a torus from the band — the furthest any point can be from it. ~1e-7 of peak, which floors
         *  to **zero quanta**: the cell has no light energy to spend (verified in `CytoTestWorldTest`). */
        data object None : Light
        /** A fraction of peak, in (0,1). Solved from the band's gaussian. */
        data class Of(val fraction: Float) : Light
    }

    private class Spec(
        val name: String,
        val x: Float,
        val y: Float,
        val type: CellType,
        val cytoplasm: Map<String, Int>,
        val biomass: Map<String, Int>?,
        val genome: List<Gene>,
        val radius: Frac,
        val sticky: Boolean,
    )

    private val specs = ArrayList<Spec>()
    private var matterLevel: Int = scenario.matterLevel
    private var matterNoise: Float = scenario.matterNoise
    private var seed: Long = 0x9E3779B97F4A7C15uL.toLong()

    /**
     * A cell, stated. [name] is how you get its id back from [Fixture.id] — nothing in the sim sees it.
     *
     * [biomass] defaults to [starterBiomassFor] the genome, as a real founder's does; pass it explicitly
     * when the *amount* is the thing under test (a division that can only just be afforded, say).
     * [at] overrides [light]-derived placement.
     */
    fun cell(
        name: String,
        genome: List<Gene> = emptyList(),
        cytoplasm: Map<String, Int> = emptyMap(),
        biomass: Map<String, Int>? = null,
        light: Light = Light.None,
        at: Pair<Float, Float>? = null,
        type: CellType = CellType.Collector,
        radius: Frac = MIN_RADIUS,
        sticky: Boolean = false,
    ): CytoTestWorld {
        require(specs.none { it.name == name }) { "duplicate cell name '$name'" }
        val x = at?.first ?: xForLight(light)
        // Cells placed by light share an x, so stack them along y — which the band ignores — far enough
        // apart that they aren't touching (contact would change the very readings a fixture exists to pin).
        val y = at?.second ?: (specs.count { at == null } * SPACING_Y)
        specs.add(Spec(name, x, y, type, cytoplasm, biomass, genome, radius, sticky))
        return this
    }

    /** The ambient matter soup. `0` is an empty world — nothing to import, nothing to passively absorb. */
    fun matter(level: Int, noise: Float = 0f): CytoTestWorld {
        matterLevel = level; matterNoise = noise
        return this
    }

    /** The world's PRNG seed. Only matters when something downstream mutates or jitters. */
    fun seed(value: Long): CytoTestWorld {
        seed = value
        return this
    }

    fun build(): Fixture {
        CytoWorldConfig.applyFrom(scenario)
        val builder = SimBuilder(SimState(randomSeed = seed))
        val positions = LinkedHashMap<String, Pair<Float, Float>>()
        for (s in specs) {
            builder.spawnCell(
                pos = CytoUnits.coord2(s.x, s.y),
                vel = Coord2.zero,
                type = s.type,
                cytoplasm = s.cytoplasm,
                biomass = s.biomass ?: starterBiomassFor(s.genome),
                logicalRadius = s.radius,
                sticky = s.sticky,
                genome = s.genome,
            )
            positions[s.name] = s.x to s.y
        }
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) {
            CytoMatterGridComponent(
                if (matterNoise > 0f) CytoMatterField.seededPerlin(matterLevel, matterNoise)
                else CytoMatterField.seededUniform(matterLevel),
            )
        }
        return Fixture(scenario, builder.build(), positions)
    }

    /**
     * A built world plus the names you gave its cells.
     *
     * [state] is the world; [id] resolves a name **against a live state**, by position rather than by a
     * stored [org.emerge.sim.core.EntityId], so it keeps working after the fixture has been round-tripped
     * through a save or handed to a controller.
     */
    class Fixture(
        val scenario: CytoScenario,
        val state: SimState,
        private val positions: Map<String, Pair<Float, Float>>,
    ) {
        val names: Set<String> get() = positions.keys

        /** The logical position the named cell was placed at. */
        fun positionOf(name: String): Pair<Float, Float> =
            positions[name] ?: error("no cell named '$name' (have ${positions.keys})")
    }

    companion object {
        /** Vertical gap between light-placed cells: comfortably more than two cell diameters. */
        private const val SPACING_Y = 4f

        /**
         * A world with **no founders** — the usual starting point, since the whole idea is to state the
         * cells rather than inherit a scenario's. Keeps the default world geometry and day/night cycle.
         */
        fun empty(scenario: CytoScenario = CytoScenario.DEFAULT): CytoTestWorld =
            CytoTestWorld(scenario.copy(founders = emptyList()))

        /** Solve for an x where the tick-0 daylight band delivers the requested illumination. */
        internal fun xForLight(light: Light): Float {
            val centre = CytoLightField.bandCenterX(0L)
            return when (light) {
                Light.Full -> centre
                // Half a span away is the furthest any point can be from the band on a torus.
                Light.None -> centre + CytoLightField.HALF
                is Light.Of -> {
                    require(light.fraction > 0f && light.fraction < 1f) { "fraction must be in (0,1)" }
                    // g = exp(-(dx/FALLOFF)^2)  =>  dx = FALLOFF * sqrt(-ln g)
                    val dx = CytoLightField.FALLOFF * kotlin.math.sqrt(-kotlin.math.ln(light.fraction.toDouble())).toFloat()
                    centre + dx.coerceAtMost(CytoLightField.HALF)
                }
            }
        }
    }
}
