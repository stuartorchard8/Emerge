package org.emerge.desktop

import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.demo.outofspace.Overlay
import org.emerge.demo.outofspace.Tool
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.render.torus.ui.Ui
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **Agent harness** — a headless, script-driven way to drive Out of Space, so an agent (or CI) can
 * build something, run the world for a stated number of ticks, and *look at what happened* without a
 * window ever opening. Modelled on `CytoAgentHarness`, which proved the shape.
 *
 * Two kinds of observation, and the split is the point:
 *
 * - **`field` / `probe` / `state` — text.** These need no GL at all and are what most fluid work
 *   actually wants. A tinted screenshot says "there is more pressure over there"; a `field pressure`
 *   prints the numbers, so "the plume is asymmetric" becomes a thing that can be *read* rather than
 *   squinted at. Every model correction this game has had so far came from two quantities disagreeing,
 *   which is a comparison you can only make on numbers.
 * - **`shot` — the real GL pipeline.** The same [OutofspaceRenderer] + [OutofspaceHud] + [Ui] the
 *   desktop host uses, rendered into a *hidden* GLFW window and read back to a PNG, so a capture is
 *   faithful (overlay colours, bitmap font, panel layout). GL is initialised lazily, on the first
 *   `shot` — so a machine with no GL context can still run every text command.
 *
 * Run: `./gradlew :apps:outofspace:desktop:outofspaceAgent --args="<script> [outDir]"` (script = a
 * file of commands, or `-` for stdin). One command per line, `#` starts a comment:
 *
 * ```
 * new                        # fresh starter vessel
 * load <path> | save <path>  # the text save format (Save.kt) — how a world gets handed over
 * run <ticks>                # advance exactly N ticks. The ONLY clock; nothing here is real-time
 * brush <kind> [dir]         # RAIL/MINER/SMELTER/VENT/... and Right|Down|Left|Up
 * place <x> <y>              # build with the current brush
 * drag <x0> <y0> <x1> <y1>   # lay a conduit run — track connects by being DRAWN, so this is not
 *                            # the same as placing each tile
 * remove <x> <y> | rotate <x> <y>
 * overlay <name>             # PLAIN/HEAT/AIR/PRESSURE/DENSITY/FLOW — what `shot` draws through
 * camera fit|centre <x> <y>|zoom <tilePx>|pan <dx> <dy>
 * field <what> [x0 y0 x1 y1] # ASCII map: pressure|density|speed|heat|air|flow|build|debris|
 *                            # species:<Name> — the only view that can show one gas settling
 * probe <x> <y>              # everything known about one tile, in full
 * trend <samples> <ticks>    # run and tabulate the conserved totals — the drift/blow-up detector
 * state [name] | shot [name] # JSON totals / PNG capture, both written to outDir
 * expect <field> <op> <value># op is = < > ; non-zero exit if any fail
 * echo <text>
 * ```
 */
object OutofspaceAgentHarness {

    private val RES_W = System.getProperty("oos.agent.w")?.toIntOrNull() ?: 1440
    private val RES_H = System.getProperty("oos.agent.h")?.toIntOrNull() ?: 900

    /** How wide an ASCII field may get before it wraps in a terminal and stops being readable. */
    private const val MAX_FIELD_COLS = 120

    fun run(scriptText: String, outDir: File) {
        outDir.mkdirs()
        val h = Session(outDir)
        try {
            for (raw in scriptText.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                runCatching { h.exec(line) }.onFailure { println("! error on '$line': ${it.message}") }
            }
        } finally {
            h.cleanup()
        }
        println("[agent] done -> ${outDir.absolutePath}")
        if (h.failures.isNotEmpty()) {
            println("[agent] ${h.failures.size} EXPECT failure(s):")
            for (f in h.failures) println("[agent]   - $f")
            throw IllegalStateException("${h.failures.size} expectation(s) failed")
        }
    }

    private class Session(val outDir: File) {

        val controller = OutofspaceController()
        val failures = ArrayList<String>()

        private val state: VesselState get() = controller.state

        /** The overlay lives on the controller, not here — that is where the HUD reads it from, and a
         *  capture whose legend disagreed with its own tints would be worse than no capture. */
        private var overlay: Overlay
            get() = controller.overlay
            set(v) { controller.overlay = v }

        // GL, and everything that needs a context. Null until the first `shot`.
        private var window: Long = NULL
        private var renderer: OutofspaceRenderer? = null
        private var hud: OutofspaceHud? = null
        private var ui: Ui? = null

        fun exec(line: String) {
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "new" -> { controller.reset(); println("[agent] new world, tick ${controller.tick}") }
                "load" -> {
                    val path = line.removePrefix("load").trim().trim('"')
                    controller.reset(Save.read(File(path).readText()))
                    println("[agent] loaded $path — tick ${controller.tick}, ${machineCount()} machines")
                }
                "save" -> {
                    val path = line.removePrefix("save").trim().trim('"')
                    File(path).writeText(Save.write(state))
                    println("[agent] saved -> $path (tick ${controller.tick})")
                }
                "run" -> {
                    val n = t[1].toInt()
                    repeat(n) { controller.stepOnce() }
                    println("[agent] ran $n ticks -> tick ${controller.tick}")
                }
                "brush" -> {
                    controller.brush = kind(t[1])
                    t.getOrNull(2)?.let { controller.brushFacing = direction(it) }
                    println("[agent] brush -> ${controller.brush.label} facing ${controller.brushFacing}")
                }
                "facing" -> { controller.brushFacing = direction(t[1]); println("[agent] facing -> ${controller.brushFacing}") }
                "place" -> { controller.place(index(t[1], t[2])); settle() }
                // Conduit joins by being DRAWN, not by touching, so laying a run has to go through the
                // controller's drag as a gesture. `place`ing each tile of a line gives disconnected track
                // that looks identical on screen — the single most confusing thing to debug by eye.
                "drag" -> {
                    controller.tool = Tool.Build
                    controller.apply(index(t[1], t[2]))
                    controller.dragTo(index(t[3], t[4]))
                    controller.endDrag()
                    settle()
                    println("[agent] drag (${t[1]},${t[2]}) -> (${t[3]},${t[4]}) with ${controller.brush.label}")
                }
                "remove" -> { controller.remove(index(t[1], t[2])); settle() }
                "rotate" -> { controller.rotate(index(t[1], t[2])); settle() }
                "overlay" -> {
                    overlay = Overlay.entries.firstOrNull { it.name.equals(t[1], true) || it.label.equals(t[1], true) }
                        ?: error("unknown overlay '${t[1]}' (have ${Overlay.entries.map { it.label }})")
                    println("[agent] overlay -> ${overlay.label}")
                }
                "camera" -> camera(t)
                "field" -> field(t[1], t.drop(2).map { it.toInt() })
                "probe" -> probe(index(t[1], t[2]))
                "trend" -> trend(t[1].toInt(), t[2].toInt())
                "state" -> dumpState(t.getOrElse(1) { "state" })
                "shot" -> shot(t.getOrElse(1) { "shot" })
                "expect" -> expect(t[1], t[2], t.getOrElse(3) { "" })
                "echo" -> println("[agent] ${line.removePrefix("echo").trim()}")
                else -> error("unknown command '${t[0]}'")
            }
        }

        /** An edit is queued, not applied — it lands on the next tick, exactly as a click does. */
        private fun settle() = controller.stepOnce()

        private fun index(x: String, y: String): Int {
            val grid = state.grid
            val ix = x.toInt(); val iy = y.toInt()
            require(grid.inBounds(ix, iy)) { "($ix,$iy) is outside the ${grid.width}x${grid.height} grid" }
            return grid.index(ix, iy)
        }

        private fun kind(name: String): MachineKind =
            MachineKind.ALL.firstOrNull { it.name.equals(name, true) || it.label.equals(name, true) }
                ?: error("unknown machine '$name' (have ${MachineKind.ALL.map { it.label }})")

        private fun direction(name: String): Direction =
            Direction.entries.firstOrNull { it.name.equals(name, true) }
                ?: error("unknown direction '$name' (have ${Direction.entries.map { it.name }})")

        private fun machineCount(): Int = state.machines.count { it != null }

        // ── camera ───────────────────────────────────────────────────────────────────
        /** Camera moves are recorded even before GL exists, so `camera` can precede the first `shot`. */
        private var pendingCamera: (OutofspaceRenderer) -> Unit = { it.centreOn(state) }

        private fun camera(t: List<String>) {
            when (t[1]) {
                "fit" -> pendingCamera = { it.centreOn(state) }
                "centre", "center" -> {
                    val x = t[2].toFloat() + 0.5f; val y = t[3].toFloat() + 0.5f
                    val px = t.getOrNull(4)?.toFloatOrNull()
                    val prev = pendingCamera
                    pendingCamera = { prev(it); if (px != null) it.focusOn(x, y, px) else it.focusOn(x, y) }
                }
                "zoom" -> { val px = t[2].toFloat(); val prev = pendingCamera; pendingCamera = { prev(it); it.focusOn(it.camX, it.camY, px) } }
                "pan" -> {
                    val dx = t[2].toFloat(); val dy = t[3].toFloat()
                    val prev = pendingCamera
                    pendingCamera = { prev(it); it.panByPixels(dx, dy) }
                }
                else -> error("camera fit|centre <x> <y>|zoom <tilePx>|pan <dx> <dy>")
            }
            println("[agent] camera ${t.drop(1).joinToString(" ")}")
        }

        // ── the ASCII field: the harness's real instrument ────────────────────────────
        /**
         * Prints a scalar (or vector) field as a grid of characters, with the legend that makes the
         * characters mean something.
         *
         * Scaled to the window's own min and max rather than to a fixed constant, for the reason the
         * FLOW overlay is: a range chosen for a settling room is useless for an exhaust plume. The
         * legend carries the absolute numbers, so two runs are still comparable — you compare the
         * legends, not the pictures.
         */
        private fun field(what: String, box: List<Int>) {
            val grid = state.grid
            val (x0, y0, x1, y1) = window(box)
            val flow = state.flow

            // `build` and `flow` are glyph maps rather than ramps: what they show is categorical
            // (which machine) or directional (which way), and a brightness ramp can show neither.
            if (what.equals("build", true) || what.equals("flow", true)) {
                val peak = if (what.equals("flow", true)) flow.peakSpeed() else 0f
                printGrid(what, x0, y0, x1, y1) { tile ->
                    if (what.equals("build", true)) buildGlyph(tile)
                    else flowGlyph(flow.xAt(tile), flow.yAt(tile), flow.speedAt(tile), peak)
                }
                if (what.equals("flow", true)) println("[agent]   peak ${"%.4f".format(peak)} tiles/tick; '.' is under 5% of it")
                else println("[agent]   . deck  # machine  = rail  B bridge  o debris  H hull")
                return
            }

            val value: (Int) -> Double = when (what.lowercase()) {
                "pressure" -> { tile -> state.air.pressureAt(tile).toDouble() }
                "density" -> { tile -> state.air.densityAt(tile).toDouble() }
                "speed" -> { tile -> flow.speedAt(tile).toDouble() }
                "heat", "temp" -> { tile -> state.kelvinAt(tile).toDouble() }
                // The air's temperature, which is a different number from the fabric's until
                // conduction couples the two -- and the one the fluid actually acts on.
                "airtemp" -> { tile -> state.airKelvinAt(tile).toDouble() }
                "air", "mass" -> { tile -> state.air.mixtureAt(tile).total.toDouble() }
                // The pipes, which are a second fluid field on the same lattice and so map exactly
                // like the room air. Worth having as its own view rather than folded into `air`: the
                // whole question about a pipe is whether what is in it is in the PIPE, and a
                // combined map cannot answer that.
                "pipe" -> { tile -> state.pipeAir.mixtureAt(tile).total.toDouble() }
                "pipetemp" -> { tile -> state.pipeAir.kelvinAt(tile).toDouble() }
                "pipepressure" -> { tile -> state.pipeAir.pressureAt(tile).toDouble() }
                "debris" -> { tile -> state.debris.massAt(tile).toDouble() }
                // One gas on its own. Bulk flow provably cannot mix or unmix, so the question
                // "has the carbon dioxide settled?" is not answerable from `density` or `air`,
                // which show the mixture — only from the species' own map.
                else -> if (what.startsWith("species:", true)) {
                    val name = what.substringAfter(':')
                    val sp = Species.ALL.firstOrNull { it.name.equals(name, true) }
                        ?: error("unknown species '$name' (have ${Species.ALL.map { it.name }})")
                    ({ tile: Int -> state.air.gramsOf(tile, sp).toDouble() })
                } else error(
                    "field pressure|density|speed|heat|airtemp|air|pipe|pipetemp|pipepressure|" +
                        "debris|species:<Name>|flow|build"
                )
            }

            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (y in y0..y1) for (x in x0..x1) {
                val v = value(grid.index(x, y))
                lo = min(lo, v); hi = max(hi, v)
            }
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            printGrid(what, x0, y0, x1, y1) { tile ->
                RAMP[((value(tile) - lo) / span * (RAMP.length - 1)).roundToInt().coerceIn(0, RAMP.length - 1)]
            }
            println("[agent]   '${RAMP.first()}' = ${fmt(lo)}   '${RAMP.last()}' = ${fmt(hi)}   (linear)")
        }

        /** For `air`, the dominant species is more use than the total — that is what "which gas" means. */
        private fun printGrid(what: String, x0: Int, y0: Int, x1: Int, y1: Int, glyph: (Int) -> Char) {
            val grid = state.grid
            println("[agent] field $what  x $x0..$x1  y $y0..$y1  tick ${controller.tick}")
            // A ruler every ten columns, so a tile can be located without counting.
            val head = StringBuilder("      ")
            for (x in x0..x1) head.append(if (x % 10 == 0) ((x / 10) % 10).digitToChar() else ' ')
            println(head)
            for (y in y0..y1) {
                val row = StringBuilder()
                row.append("%4d  ".format(y))
                for (x in x0..x1) row.append(glyph(grid.index(x, y)))
                println(row)
            }
        }

        private fun buildGlyph(tile: Int): Char {
            state.bridges[tile]?.let { return 'B' }
            state.rails[tile]?.let { return '=' }
            val m = state.machineCovering(tile)
            if (m != null) return if (m::class.simpleName == "Hull") 'H' else '#'
            if (state.debris.massAt(tile) > 0L) return 'o'
            return '.'
        }

        private fun flowGlyph(fx: Long, fy: Long, speed: Float, peak: Float): Char {
            if (peak <= 0f || speed < peak * 0.05f) return '.'
            val ax = abs(fx); val ay = abs(fy)
            // Diagonals only when the two components are genuinely comparable; otherwise a mostly-
            // horizontal draught reads as diagonal and the picture lies about where the air is going.
            return when {
                ax > ay * 2 -> if (fx > 0) '>' else '<'
                ay > ax * 2 -> if (fy > 0) 'v' else '^'   // +y is DOWN: side-on world, screen-down is gravity-down
                fx > 0 && fy > 0 -> '\\'
                fx > 0 -> '/'
                fy > 0 -> '/'
                else -> '\\'
            }
        }

        /**
         * The window a field prints: the argument box if given, otherwise everything built plus a
         * margin, because the interesting air is the air just outside the hull.
         */
        private fun window(box: List<Int>): List<Int> {
            val grid = state.grid
            if (box.size == 4) return listOf(
                box[0].coerceIn(0, grid.width - 1), box[1].coerceIn(0, grid.height - 1),
                box[2].coerceIn(0, grid.width - 1), box[3].coerceIn(0, grid.height - 1),
            )
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (i in state.machines.indices) {
                if (state.machines[i] == null && state.rails[i] == null) continue
                val x = grid.xOf(i); val y = grid.yOf(i)
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
            if (minX > maxX) return listOf(0, 0, min(grid.width, MAX_FIELD_COLS) - 1, grid.height - 1)
            val pad = 4
            val x0 = max(0, minX - pad); val y0 = max(0, minY - pad)
            return listOf(
                x0, y0,
                min(grid.width - 1, min(maxX + pad, x0 + MAX_FIELD_COLS - 1)),
                min(grid.height - 1, maxY + pad),
            )
        }

        private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v)

        // ── one tile, in full ─────────────────────────────────────────────────────────
        /**
         * Everything the world knows about a single tile.
         *
         * The counterpart to [field]: a field says *where* to look, this says what is actually there.
         * All four fluid quantities together, deliberately — pressure and density disagreeing is the
         * observation that has paid for itself most often (it is why `stratifyColumns` could go).
         */
        private fun probe(tile: Int) {
            val grid = state.grid
            val x = grid.xOf(tile); val y = grid.yOf(tile)
            val air = state.air.mixtureAt(tile)
            println("[agent] probe ($x,$y) tile $tile @ tick ${controller.tick}")
            println("[agent]   machine   ${state.machineCovering(tile)?.let { it::class.simpleName } ?: "-"}" +
                "  rail ${state.rails[tile]?.let { "yes held=${it.held != null}" } ?: "-"}" +
                "  bridge ${if (state.bridges[tile] != null) "yes" else "-"}")
            println("[agent]   debris    ${state.debris.massAt(tile)}g")
            println("[agent]   heat      ${state.kelvinAt(tile)}K  air ${state.airKelvinAt(tile)}K")
            println("[agent]   pressure  ${state.air.pressureAt(tile)} mmol")
            println("[agent]   density   ${state.air.densityAt(tile)}")
            println("[agent]   air       ${air.total}g  ${composition(air)}")
            println("[agent]   flow      x=${state.flow.xAt(tile)} y=${state.flow.yAt(tile)} " +
                "speed=${"%.5f".format(state.flow.speedAt(tile))} tiles/tick")
        }

        private fun composition(m: org.emerge.demo.outofspace.chem.Mixture): String =
            if (m.total == 0L) "empty" else Species.ALL
                .filter { m[it] > 0L }
                .sortedByDescending { m[it] }
                .joinToString("  ") { "${it.name} ${m[it] * 100 / m.total}%" }

        // ── conservation over time ───────────────────────────────────────────────────
        /**
         * Runs the world and tabulates the totals that are supposed to balance.
         *
         * A blow-up and a slow leak look identical in a single snapshot and obvious in a column of
         * them, and both are the failure mode a fluid solver actually has. Peak speed is here for the
         * same reason: an undamped Jacobi iteration fails *silently*, and the first thing it does is
         * grow.
         */
        private fun trend(samples: Int, ticksEach: Int) {
            println("[agent] trend: %8s %12s %10s %12s %10s %10s".format(
                "tick", "airGrams", "dAir", "storedJ", "peakSpd", "debris"))
            var lastAir = state.atmosphereGrams
            repeat(samples) {
                repeat(ticksEach) { controller.stepOnce() }
                val air = state.atmosphereGrams
                println("[agent]        %8d %12d %10d %12d %10.5f %10d".format(
                    controller.tick, air, air - lastAir, state.storedJoules, state.flow.peakSpeed(), state.debrisGrams))
                lastAir = air
            }
            println("[agent]   baseline air ${state.baselineAirGrams}g, vented ${state.airVentedGrams}g " +
                "(balance ${state.atmosphereGrams + state.airVentedGrams - state.baselineAirGrams}g)")
        }

        // ── observations ─────────────────────────────────────────────────────────────
        private fun reading(field: String): Double? = when (field) {
            "tick" -> controller.tick.toDouble()
            "machines" -> machineCount().toDouble()
            // Rooms and pipes together, because they share one ledger and `airBalance` below is
            // that ledger. `pipeGrams` separates them for a script that cares which side gas is on.
            "airGrams" -> state.atmosphereGrams.toDouble()
            "pipeGrams" -> state.pipeAir.totalGrams.toDouble()
            "airVented" -> state.airVentedGrams.toDouble()
            "airBalance" -> (state.atmosphereGrams + state.airVentedGrams - state.baselineAirGrams).toDouble()
            "debrisGrams" -> state.debrisGrams.toDouble()
            "minedGrams" -> state.minedGrams.toDouble()
            "ventedGrams" -> state.ventedGrams.toDouble()
            "inTransitGrams" -> state.inTransitGrams.toDouble()
            "stockpileGrams" -> state.stockpile.totalGrams.toDouble()
            "storedJoules" -> state.storedJoules.toDouble()
            "generatedJoules" -> state.generatedJoules.toDouble()
            "radiatedJoules" -> state.radiatedJoules.toDouble()
            "solidToAirJoules" -> state.solidToAirJoules.toDouble()
            // The whole solid balance as one number, so a script can `expect heatBalance == 0`
            // rather than reassembling five terms. Zero, always — see [VesselState.baselineJoules].
            "heatBalance" -> (
                state.storedJoules + state.radiatedJoules + state.solidToAirJoules -
                    state.generatedJoules - state.constructionJoules - state.baselineJoules
                ).toDouble()
            "airHeatBalance" -> (
                state.air.totalJoules + state.airVentedJoules - state.solidToAirJoules -
                    state.baselineAirJoules
                ).toDouble()
            "hottestSolidK" -> (state.bodies.maxOfOrNull { it.kelvin } ?: 0).toDouble()
            "hottestAirK" -> (0 until state.grid.size).maxOf { state.airKelvinAt(it) }.toDouble()
            "peakSpeed" -> state.flow.peakSpeed().toDouble()
            "impulseX" -> state.vesselImpulseX.toDouble()
            "impulseY" -> state.vesselImpulseY.toDouble()
            else -> null
        }

        private val FIELDS = listOf(
            "tick", "machines", "airGrams", "pipeGrams", "airVented", "airBalance", "debrisGrams", "minedGrams",
            "ventedGrams", "inTransitGrams", "stockpileGrams", "storedJoules", "generatedJoules",
            "radiatedJoules", "solidToAirJoules", "heatBalance", "airHeatBalance",
            "hottestSolidK", "hottestAirK", "peakSpeed", "impulseX", "impulseY",
        )

        private fun dumpState(name: String) {
            val sb = StringBuilder("{\n")
            for ((i, f) in FIELDS.withIndex()) {
                sb.append("  \"$f\": ${fmt(reading(f)!!)}")
                sb.append(if (i < FIELDS.size - 1) ",\n" else "\n")
            }
            sb.append("}\n")
            println(sb)
            File(outDir, "$name.json").writeText(sb.toString())
        }

        /**
         * `expect <field> <op> <value>` — so a script is a **test** and not just a recording.
         *
         * An operator rather than plain equality, because most of what is worth asserting about a
         * simulation is a bound: air is conserved to within a rounding error, the solver has not run
         * away, the plume has actually moved something. Exact equality on a float is a test that
         * fails for being right.
         */
        private fun expect(field: String, op: String, want: String) {
            val got = reading(field)
            if (got == null) {
                failures.add("expect: unknown field '$field' (have $FIELDS)")
                println("[agent] EXPECT ?? unknown field '$field'")
                return
            }
            val target = want.toDoubleOrNull()
            if (target == null) { failures.add("expect: '$want' is not a number"); return }
            val ok = when (op) {
                "=", "==" -> got == target
                "<" -> got < target
                ">" -> got > target
                "<=" -> got <= target
                ">=" -> got >= target
                else -> { failures.add("expect: unknown operator '$op' (= < > <= >=)"); return }
            }
            if (ok) println("[agent] EXPECT ok   $field (${fmt(got)}) $op $want")
            else {
                println("[agent] EXPECT FAIL $field = ${fmt(got)}, wanted $op $want")
                failures.add("$field = ${fmt(got)}, wanted $op $want")
            }
        }

        // ── faithful GL render -> PNG ─────────────────────────────────────────────────
        /**
         * Brings up the GL context and the real renderer, on first use only.
         *
         * Lazy because everything above this line is arithmetic: a container with no GL can still run
         * a whole fluid investigation, and only `shot` should be able to fail for want of a driver.
         */
        private fun ensureGl(): Triple<OutofspaceRenderer, OutofspaceHud, Ui> {
            renderer?.let { return Triple(it, hud!!, ui!!) }
            if (!glfwInit()) error("GLFW init failed")
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            window = glfwCreateWindow(RES_W, RES_H, "outofspace-agent", NULL, NULL)
            if (window == NULL) error("failed to create hidden GLFW window (no GL context available?)")
            glfwMakeContextCurrent(window)
            org.lwjgl.opengl.GL.createCapabilities()
            val r = OutofspaceRenderer()
            val h = OutofspaceHud()
            val u = Ui()
            r.setResolution(RES_W.toFloat(), RES_H.toFloat())
            u.setResolution(RES_W.toFloat(), RES_H.toFloat())
            r.centreOn(state)
            renderer = r; hud = h; ui = u
            return Triple(r, h, u)
        }

        private fun shot(name: String) {
            val (r, h, u) = ensureGl()
            pendingCamera(r)
            glViewport(0, 0, RES_W, RES_H)
            // tickAlpha 1: the tick has landed. A capture must show where things ARE, not an
            // interpolated position that corresponds to no state the sim was ever in.
            r.draw(state, -1, overlay, 1f)
            h.build(u, controller, fps = 0f, hovered = -1)
            u.draw()
            glFinish()

            val buf = BufferUtils.createByteBuffer(RES_W * RES_H * 4)
            glReadPixels(0, 0, RES_W, RES_H, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            val img = BufferedImage(RES_W, RES_H, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until RES_H) {
                val src = RES_H - 1 - y                        // GL rows are bottom-up
                for (x in 0 until RES_W) {
                    val i = (src * RES_W + x) * 4
                    val red = buf.get(i).toInt() and 0xFF
                    val green = buf.get(i + 1).toInt() and 0xFF
                    val blue = buf.get(i + 2).toInt() and 0xFF
                    img.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
                }
            }
            val out = File(outDir, "$name.png")
            ImageIO.write(img, "png", out)
            println("[agent] shot -> ${out.absolutePath} (${overlay.label}, tick ${controller.tick})")
        }

        fun cleanup() {
            if (window != NULL) {
                runCatching { renderer?.cleanup(); ui?.cleanup() }
                glfwDestroyWindow(window)
                glfwTerminate()
            }
        }
    }

    /** Ten levels, dimmest first. Enough to see a gradient, few enough that each step is distinct. */
    private const val RAMP = " .:-=+*#%@"
}

private operator fun <T> List<T>.component4(): T = this[3]

fun main(args: Array<String>) {
    val scriptArg = args.getOrElse(0) { "-" }
    val outDir = File(args.getOrElse(1) { "agent-out" })
    val script = if (scriptArg == "-") System.`in`.readBytes().decodeToString() else File(scriptArg).readText()
    OutofspaceAgentHarness.run(script, outDir)
}
