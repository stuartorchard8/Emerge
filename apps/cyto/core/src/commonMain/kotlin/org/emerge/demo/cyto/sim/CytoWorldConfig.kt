package org.emerge.demo.cyto.sim

/**
 * **Per-run world geometry + day/night**, held as process-global runtime state rather than the fixed
 * [CytoTuning] / [CytoUnits] compile-time constants — the values the title-screen *New* flow (and a loaded
 * save) get to pick before a world is built. Everything that used to read the hard constants
 * (`CytoUnits.CELLS_PER_AXIS`, `CytoTuning.LIGHT_FALLOFF`, `CytoTuning.LIGHT_ORBIT_PERIOD`,
 * `CytoMatterField.BASE_RES`) now reads *here*, so those knobs become live without threading a config object
 * through every call site.
 *
 * **Determinism contract:** the defaults reproduce the old constants byte-for-byte
 * (`cellsPerAxis = 64`, `orbitPeriod = 3600`, `dayFraction = 0.25` ⇒ `LIGHT_FALLOFF = 64/4 = 16`), and
 * [createCytoInitialState] with no scenario [reset]s to them — so `CytoGoldenTest` and every cfg-driven
 * probe are unchanged. Only the *New/Custom* path (and [applyFrom] on load) ever mutates this.
 *
 * **Concurrency:** written only on the main/UI thread while the sim thread is stopped (a world rebuild), read
 * everywhere after — publication happens-before the (freshly-started) sim thread, so plain `var`s suffice and
 * the hot-path reads stay a bare field load (no volatile barrier in the biology inner loop).
 */
object CytoWorldConfig {
    const val DEFAULT_CELLS_PER_AXIS = 64
    const val DEFAULT_ORBIT_PERIOD = 3600L
    /** Daylight band half-width as a fraction of the torus half-span; 0.25 ⇒ the old `CELLS_PER_AXIS/4` falloff. */
    const val DEFAULT_DAY_FRACTION = 0.25f

    /** Base-cell diameters across the torus per axis (the old `CytoUnits.CELLS_PER_AXIS`). */
    var cellsPerAxis: Int = DEFAULT_CELLS_PER_AXIS
        private set

    /** Ticks for the daylight band to sweep once around the torus (a full day+night). */
    var orbitPeriod: Long = DEFAULT_ORBIT_PERIOD
        private set

    /** Fraction of the sweep that is "day" — sets the light band's half-width (see [DEFAULT_DAY_FRACTION]). */
    var dayFraction: Float = DEFAULT_DAY_FRACTION
        private set

    /** Matter quad-tree base tiles per axis, coupled to [cellsPerAxis] so the *finest* leaf stays the same
     *  logical size — a cell always overlaps the same number of matter cells regardless of world size, so its
     *  matter footprint (and thus its exchange dynamics) is invariant. `64/16 = 4` at the default. */
    val matterBaseRes: Int get() = (cellsPerAxis / 16).coerceAtLeast(1)

    /** The geometry [applyFrom] would install for [scenario], without installing it — so a caller can ask
     *  "is this stored world still the one this scenario describes?" (see `CytoSaves.geometryMatches`). */
    fun geometryOf(scenario: CytoScenario): Geometry {
        val orbit = (scenario.dayTicks + scenario.nightTicks).coerceAtLeast(1L)
        return Geometry(
            cellsPerAxis = scenario.worldSize.coerceAtLeast(16),
            orbitPeriod = orbit,
            dayFraction = (scenario.dayTicks.toFloat() / orbit.toFloat()).coerceIn(0.02f, 0.98f),
        )
    }

    /** The three values that make a world's geometry — what a save's `.world` sidecar carries. */
    data class Geometry(val cellsPerAxis: Int, val orbitPeriod: Long, val dayFraction: Float)

    fun applyFrom(scenario: CytoScenario) {
        val g = geometryOf(scenario)
        cellsPerAxis = g.cellsPerAxis
        orbitPeriod = g.orbitPeriod
        dayFraction = g.dayFraction
    }

    /** Restore the geometry saved on a loaded world (see [CytoSimParamsComponent]). */
    fun applyFrom(cellsPerAxis: Int, orbitPeriod: Long, dayFraction: Float) {
        this.cellsPerAxis = cellsPerAxis.coerceAtLeast(16)
        this.orbitPeriod = orbitPeriod.coerceAtLeast(1L)
        this.dayFraction = dayFraction.coerceIn(0.02f, 0.98f)
    }

    fun reset() {
        cellsPerAxis = DEFAULT_CELLS_PER_AXIS
        orbitPeriod = DEFAULT_ORBIT_PERIOD
        dayFraction = DEFAULT_DAY_FRACTION
    }
}
