package org.emerge.desktop

import org.emerge.demo.fluidlab.AMBIENT_KELVIN
import org.emerge.demo.fluidlab.FluidlabConfig
import org.emerge.demo.fluidlab.FluidlabController
import org.emerge.demo.fluidlab.FluidlabState
import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.fluid.AMBIENT_PRESSURE
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * **Agent harness** — a headless, script-driven way to drive Fluidlab, so an agent (or CI) can build
 * a situation, run it for a stated number of ticks, and *read what happened* without a window ever
 * opening.
 *
 * Deliberately text-only, and that is the lesson carried over from Out of Space's harness rather than
 * an economy: for fluid work the numbers are the point. A screenshot says "there is more pressure over
 * there"; `field pressure` prints the values, so "the plume is asymmetric" becomes something you can
 * *read* instead of squint at. Every model correction this simulation has had came from two quantities
 * disagreeing, and that is a comparison you can only make on numbers. If a picture is ever genuinely
 * wanted, the desktop host draws the same state.
 *
 * Run: `./gradlew :apps:fluidlab:desktop:fluidlabAgent --args="<scriptFile|->"`.
 * One command per line, `#` starts a comment, blank lines ignored:
 *
 * ```
 * new sealed 24 16        # a sealed room of ordinary air, 24x16 (default 32x24)
 * new vacuum              # empty grid, no walls, no air
 * gravity none            # or `down`. Rebuilds the world — set it before you build.
 * latent on               # boiling/condensing cost and release energy
 *
 * wall 5 5 on             # place/remove one wall tile
 * box 4 4 12 10           # wall rectangle outline (the cheap way to make a room)
 * breach 12 4             # remove a wall — the hole everything interesting starts with
 * inject 8 8 Water 5000 400    # grams of a species at a temperature
 * evacuate 8 8            # instant local vacuum
 * heat 8 8 100000         # joules into the air, no mass
 *
 * run 200                 # advance the sim
 *
 * field pressure          # ASCII map: density|pressure|heat|flow|species
 * probe 8 8               # every number for one tile
 * state                   # totals, ledger and the solver's error terms
 * expect mass == 1234     # assert; a failure exits non-zero so CI notices
 * ```
 *
 * `expect` is what turns a script into an acceptance test. Supported subjects: `mass`, `joules`,
 * `vented`, `substeps`, `undelivered`, `tick`, `cohesionunpaid`. Operators: `==`, `!=`, `<`, `<=`,
 * `>`, `>=`, and `~` for "within 1%" — the last is the one to reach for when asserting on a solver,
 * because pinning an exact literal to a discretisation is how a test becomes a tuning fight.
 */
fun main(args: Array<String>) {
    val source = args.getOrNull(0) ?: "-"
    val lines = if (source == "-") generateSequence(::readLine).toList() else File(source).readLines()

    val harness = Harness()
    var failures = 0
    for ((n, raw) in lines.withIndex()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        try {
            harness.command(line)
        } catch (e: ExpectationFailed) {
            println("FAIL line ${n + 1}: ${e.message}")
            failures++
        } catch (e: Exception) {
            println("ERROR line ${n + 1}: `$line` — ${e.message}")
            failures++
        }
    }
    if (failures > 0) {
        println("$failures failure(s)")
        exitProcess(1)
    }
}

private class ExpectationFailed(message: String) : Exception(message)

private class Harness {
    private var cfg = FluidlabConfig()
    private var controller = FluidlabController(cfg, FluidlabState.sealedRoom(cfg))

    private val state: FluidlabState get() = controller.state

    fun command(line: String) {
        val parts = line.split(Regex("\\s+"))
        when (parts[0].lowercase()) {
            "new" -> newWorld(parts)
            "gravity" -> rebuild(cfg.copy(gravity = if (parts[1].lowercase() == "none") FluidlabConfig.FREEFALL else FluidlabConfig.DOWN))
            "latent" -> rebuild(cfg.copy(latentHeat = parts[1].lowercase() == "on"))
            "wall" -> controller.setWall(tile(parts[1], parts[2]), parts.getOrNull(3)?.lowercase() != "off")
            "breach" -> controller.setWall(tile(parts[1], parts[2]), false)
            "box" -> box(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
            "inject" -> controller.inject(
                tile(parts[1], parts[2]),
                Species.valueOf(parts[3]),
                parts[4].toLong(),
                parts.getOrNull(5)?.toInt() ?: AMBIENT_KELVIN,
            )
            "evacuate" -> controller.evacuate(tile(parts[1], parts[2]))
            "heat" -> controller.heat(tile(parts[1], parts[2]), parts[3].toLong())
            "run" -> controller.stepTicks(parts[1].toInt())
            "field" -> field(parts[1].lowercase())
            "probe" -> probe(tile(parts[1], parts[2]))
            "state" -> printState()
            "expect" -> expect(parts[1].lowercase(), parts[2], parts[3].toLong())
            else -> error("unknown command '${parts[0]}'")
        }
    }

    private fun newWorld(parts: List<String>) {
        val kind = parts.getOrNull(1)?.lowercase() ?: "sealed"
        val w = parts.getOrNull(2)?.toInt() ?: cfg.width
        val h = parts.getOrNull(3)?.toInt() ?: cfg.height
        cfg = cfg.copy(width = w, height = h)
        val fresh = if (kind == "vacuum") FluidlabState.vacuum(cfg) else FluidlabState.sealedRoom(cfg)
        controller = FluidlabController(cfg, fresh)
    }

    /**
     * Config is fixed for a controller's lifetime (the reducer takes it per tick, but the stepper
     * holds one), so changing gravity or latent heat means a new world. Stated rather than hidden:
     * a script that flips gravity mid-run and expects continuity would otherwise be quietly wrong.
     */
    private fun rebuild(next: FluidlabConfig) {
        cfg = next
        controller = FluidlabController(cfg, FluidlabState.sealedRoom(cfg))
    }

    private fun tile(xs: String, ys: String): Int {
        val x = xs.toInt()
        val y = ys.toInt()
        require(state.grid.inBounds(x, y)) { "($x,$y) is outside the ${state.grid.width}x${state.grid.height} grid" }
        return state.grid.index(x, y)
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (x in x0..x1) {
            controller.setWall(state.grid.index(x, y0), true)
            controller.setWall(state.grid.index(x, y1), true)
        }
        for (y in y0..y1) {
            controller.setWall(state.grid.index(x0, y), true)
            controller.setWall(state.grid.index(x1, y), true)
        }
    }

    // ── Observation ──────────────────────────────────────────────────────────────

    private fun field(which: String) {
        val grid = state.grid
        val air = state.air
        val edges = EdgeGrid(grid)
        println("$which  (${grid.width}x${grid.height}, tick ${state.tick})")
        for (y in 0 until grid.height) {
            val sb = StringBuilder()
            for (x in 0 until grid.width) {
                val t = grid.index(x, y)
                if (state.walls[t] != null) { sb.append('#'); continue }
                val f = when (which) {
                    "density" -> air.densityAt(t).toDouble() / AirField.AMBIENT_AIR.total
                    "pressure" -> air.pressureAt(t).toDouble() / AMBIENT_PRESSURE
                    "heat" -> (air.kelvinAt(t) - AMBIENT_KELVIN) / 60.0 + 1.0
                    "flow" -> {
                        val vx = (state.momentumX[edges.xEdge(x, y)] + state.momentumX[edges.xEdge(x + 1, y)]) * 0.5
                        val vy = (state.momentumY[edges.yEdge(x, y)] + state.momentumY[edges.yEdge(x, y + 1)]) * 0.5
                        sqrt(vx * vx + vy * vy) / FLOW_REFERENCE
                    }
                    "species" -> {
                        sb.append(dominant(air, t)); continue
                    }
                    else -> error("unknown field '$which' (density|pressure|heat|flow|species)")
                }
                sb.append(glyph(f))
            }
            println(sb)
        }
        println("  legend: '${RAMP}' low→high, 1.0 ambient ≈ '${glyph(1.0)}', '#' wall, '.' empty")
    }

    /** First letter of the heaviest species present, so a mixture reads at a glance. */
    private fun dominant(air: AirField, tile: Int): Char {
        var best: Species? = null
        var bestGrams = 0L
        for (s in Species.ALL) {
            val g = air.gramsOf(tile, s)
            if (g > bestGrams) { bestGrams = g; best = s }
        }
        return best?.name?.first() ?: '.'
    }

    private fun glyph(f: Double): Char {
        if (f <= 0.0) return '.'
        // Ambient sits mid-ramp so both directions are visible; a linear scale from zero would put
        // every ordinary reading in the same two characters and hide exactly what is interesting.
        val i = ((f / 2.0) * (RAMP.length - 1)).toInt().coerceIn(0, RAMP.length - 1)
        return RAMP[i]
    }

    private fun probe(tile: Int) {
        val air = state.air
        val x = state.grid.xOf(tile)
        val y = state.grid.yOf(tile)
        println("tile ($x,$y) index=$tile ${if (state.walls[tile] != null) "WALL" else ""}")
        println("  pressure   ${air.pressureAt(tile)}  (ambient $AMBIENT_PRESSURE)")
        println("  density    ${air.densityAt(tile)} g  (ambient ${AirField.AMBIENT_AIR.total})")
        println("  kelvin     ${air.kelvinAt(tile)}")
        println("  capacity   ${air.heatCapacityAt(tile)}")
        for (s in Species.ALL) {
            val g = air.gramsOf(tile, s)
            if (g != 0L) println("  ${s.name.padEnd(14)} $g g")
        }
    }

    private fun printState() {
        val r = state.report
        println("tick        ${state.tick}")
        println("mass        ${state.totalGrams()} g")
        println("joules      ${state.totalJoules()}")
        println("vented      ${state.totalVentedGrams} g / ${state.totalVentedJoules} J")
        println("hull        ${r.vesselX}, ${r.vesselY}")
        println("escaped     ${r.escapedX}, ${r.escapedY}")
        println("undelivered ${r.undeliveredX}, ${r.undeliveredY}")
        println("substeps    ${r.subSteps}")
        println("cohesion    ${r.cohesionUnpaid} unpaid")
    }

    private fun expect(subject: String, op: String, want: Long) {
        val r = state.report
        val got = when (subject) {
            "mass" -> state.totalGrams()
            "joules" -> state.totalJoules()
            "vented" -> state.totalVentedGrams
            "substeps" -> r.subSteps.toLong()
            "undelivered" -> abs(r.undeliveredX) + abs(r.undeliveredY)
            "tick" -> state.tick
            "cohesionunpaid" -> r.cohesionUnpaid
            else -> error("unknown subject '$subject'")
        }
        val ok = when (op) {
            "==" -> got == want
            "!=" -> got != want
            "<" -> got < want
            "<=" -> got <= want
            ">" -> got > want
            ">=" -> got >= want
            "~" -> abs(got - want) * 100 <= abs(want).coerceAtLeast(1)
            else -> error("unknown operator '$op'")
        }
        if (!ok) throw ExpectationFailed("$subject $op $want, but $subject is $got")
        println("ok: $subject $op $want")
    }

    companion object {
        private const val RAMP = ".:-=+*#%@"

        /**
         * Face momentum that reads as full-scale in `field flow`. Fixed rather than auto-scaled to the
         * frame's peak, unlike the GL overlay: a script compares one run's output against another's,
         * and a per-frame scale would silently redefine the units between them.
         */
        private const val FLOW_REFERENCE = 40_000.0
    }
}
