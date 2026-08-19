package org.emerge.desktop

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.diffuseFluid
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.massPerTileOf
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.tileMass
import org.emerge.demo.outofspace.world.tilePressure

/**
 * Where the tick's time goes, and how much of it is proportional to [Species.COUNT].
 *
 * This exists to answer one question before any sparse-Mixture work starts: **which** of the
 * species-linear structures actually dominates. There are three candidates and they want opposite
 * fixes, so guessing wrong means rewriting the wrong file:
 *
 *  1. the dense per-tile fields (`tiles × COUNT` longs) swept by diffusion, pressure and heat;
 *  2. [Mixture]'s full-width `LongArray` allocation, paid per belt packet and per rock;
 *  3. everything else, in which case species count is not the wall and the answer is "don't bother".
 *
 * Two numbers come out. `TICK` is the honest whole-sim baseline — the denominator, without which a
 * subsystem's microseconds mean nothing. The `— species-linear —` block times the suspects in
 * isolation on state pulled from a warmed, running world, so their share of `TICK` is a measured
 * fraction rather than an inferred one.
 *
 * ⚠️ **The slope is the point, not the level.** A single run at the current [Species.COUNT] cannot
 * tell you which structure is the wall, because at 14 species all three are cheap. Run it, append
 * filler species to the [Species] enum, and run it again — the subsystem whose time grows fastest
 * between the two is the one to fix. Appending is safe: ordinals are append-only by contract, saves
 * write species by name, and a filler with `relativeAbundance = 0` stays out of `Species.NATURAL`
 * so no rock will contain it. That last part is deliberate — an absent species still costs a read
 * and a branch in [diffuseFluid]'s inner loop, and separating "declared" from "present" is exactly
 * the distinction that decides whether laziness is enough or sparseness is required.
 */
object OutofspaceBench {

    /** Discarded. Long enough for JIT to settle and for the starter vessel to stop being pristine. */
    private const val WARMUP_TICKS = 200

    fun run(ticks: Int, innerReps: Int) {
        val controller = OutofspaceController()
        controller.reset(starterVessel(controller.cfg.initialGrid))

        repeat(WARMUP_TICKS) { controller.stepOnce() }

        val state = controller.state
        val grid = state.grid
        val tiles = grid.size
        println("[bench] ${Species.COUNT} species, ${grid.width}x${grid.height} = $tiles tiles, " +
            "$ticks ticks, ${state.bodies.size} bodies")
        println("[bench] dense field = ${tiles.toLong() * Species.COUNT * 8 / 1024} KiB each")
        println()

        // ── The denominator ──
        val tickNanos = time(ticks) { controller.stepOnce() }
        report("TICK (whole sim)", tickNanos, ticks)
        println()
        println("  — species-linear —")

        // ── The suspects, on real state from the warmed world ──
        val edges = EdgeGrid(grid)
        val structure = StructureMap.derive(grid, state.deck)
        val apertures = ApertureField.derive(edges, structure)
        val mass = state.air.copyMass()
        val energy = state.air.copyEnergy()

        // Diffusion is the big dense sweep: tiles × COUNT × SUB_STEPS, and the zero-skip is inside
        // the species loop, so a declared-but-empty species is not free here.
        val diffuse = time(innerReps) {
            diffuseFluid(edges, apertures, mass.copyOf(), energy.copyOf())
        }
        share("diffuseFluid (air)", diffuse, innerReps, tickNanos, ticks)

        val capacity = heatCapacity(tiles, mass)
        val kelvin = gasKelvin(energy, capacity)
        share("tilePressure", time(innerReps) { tilePressure(tiles, mass, kelvin) }, innerReps, tickNanos, ticks)
        share("gasCapacity", time(innerReps) { heatCapacity(tiles, mass) }, innerReps, tickNanos, ticks)
        share("tileMass", time(innerReps) { tileMass(tiles, mass) }, innerReps, tickNanos, ticks)

        // ── The same sweeps, with every species actually present ──
        //
        // The block above measures a *declared* species; this one measures a *present* one. They are
        // different questions with different answers, and conflating them is how you conclude that a
        // dense array is fine right up until the world fills one. Every inner loop in the sim skips
        // zero, so the block above is largely measuring branches — this is the real per-species cost.
        val full = mass.copyOf()
        for (tile in grid.tiles) {
            val base = tile.index * Species.COUNT
            // Only where there is already air: filling vacuum would change which tiles are worked at
            // all, and then this would measure a different world rather than a fuller one.
            if (full.data.sliceOfIsEmpty(base)) continue
            for (f in Fluid.ALL) if (full[MassIndex(tile, f)] == 0L) full[MassIndex(tile, f)] = 1_000L
        }
        val fullEnergy = energy.copyOf()
        println()
        println("  — same, every species present —")
        share("diffuseFluid (air)", time(innerReps) {
            diffuseFluid(edges, apertures, full.copyOf(), fullEnergy.copyOf())
        }, innerReps, tickNanos, ticks)
        val fullKelvin = gasKelvin(fullEnergy, heatCapacity(tiles, full))
        share("tilePressure", time(innerReps) { tilePressure(tiles, full, fullKelvin) }, innerReps, tickNanos, ticks)
        share("gasCapacity", time(innerReps) { heatCapacity(tiles, full) }, innerReps, tickNanos, ticks)
        share("tileMass", time(innerReps) { tileMass(tiles, full) }, innerReps, tickNanos, ticks)

        println()
        println("  — Mixture (allocation-bound; per op, not per tick) —")

        // Representative of what a belt actually does: a two-species ore mixture, merged and split.
        val ore = Mixture.of(Species.Iron to 700_000L, Species.Quartz to 300_000L, energy = 0)
        val other = Mixture.of(Species.Iron to 120_000L, Species.Copper to 40_000L, energy = 0)
        val ops = innerReps * 100
        perOp("plus", time(ops) { ore + other }, ops)
        perOp("take (apportion)", time(ops) { ore.take(250_000L) }, ops)
        perOp("massPerTileOf", time(ops) { massPerTileOf(ore) }, ops)
        perOp("dominant", time(ops) { ore.dominant }, ops)
    }

    /** Total nanoseconds for [reps] of [body]. `blackhole` keeps a pure call from being elided. */
    private inline fun time(reps: Int, body: () -> Any?): Long {
        val start = System.nanoTime()
        for (i in 0 until reps) blackhole = body()
        return System.nanoTime() - start
    }

    @Volatile
    private var blackhole: Any? = null

    private fun report(label: String, nanos: Long, reps: Int) {
        println("  %-24s %8.3f ms".format(label, nanos / 1e6 / reps))
    }

    /** A subsystem's cost, and what fraction of a whole tick that is — the number that ranks it. */
    private fun share(label: String, nanos: Long, reps: Int, tickNanos: Long, tickReps: Int) {
        val per = nanos.toDouble() / reps
        val tick = tickNanos.toDouble() / tickReps
        println("  %-24s %8.3f ms   %5.1f%% of tick".format(label, per / 1e6, 100.0 * per / tick))
    }

    /** True if this tile's whole species slice is zero — i.e. vacuum, and not somewhere to add air. */
    private fun LongArray.sliceOfIsEmpty(base: Int): Boolean {
        for (s in 0 until Species.COUNT) if (this[base + s] != 0L) return false
        return true
    }

    private fun perOp(label: String, nanos: Long, reps: Int) {
        println("  %-24s %8.0f ns/op".format(label, nanos.toDouble() / reps))
    }
}

fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 300
    val innerReps = args.getOrNull(1)?.toIntOrNull() ?: 2000
    OutofspaceBench.run(ticks, innerReps)
}
