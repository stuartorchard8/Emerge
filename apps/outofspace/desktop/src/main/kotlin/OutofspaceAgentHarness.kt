package org.emerge.desktop

import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.Mode
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.DeleteLayer
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.demo.outofspace.Overlay
import org.emerge.demo.outofspace.Tool
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.Brush
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
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
 * new [rocks]                # fresh starter vessel. `rocks` is how many are scattered in the space
 *                            # around it (default a field of them); `new 0` is an empty sky, which
 *                            # is what a script that counts or weighs its own rocks wants
 * load <path> | save <path>  # the text save format (Save.kt) — how a world gets handed over
 * run <ticks>                # advance exactly N ticks. The ONLY clock; nothing here is real-time
 * brush <kind> [dir]         # RAIL/EXTRACTOR/SMELTER/VENT/... and Right|Down|Left|Up
 * place <x> <y>              # build with the current brush
 * fit                        # shrink grid back to ship + pad
 * drag <x0> <y0> <x1> <y1>   # lay a conduit run — track connects by being DRAWN, so this is not
 *                            # the same as placing each tile
 * remove <x> <y> [layer]    # layer = TOP|BRIDGE|RAIL|PIPE|DECK|ALL (default TOP, one layer/click)
 * rotate <x> <y>
 * wire <x> <y> <channel> <permille>  # append one RUN term. ALWAYS@1000 is "hold the button down",
 *                            # which is how a script opens an airlock — they ship wired to nothing
 * inject <x> <y> [ticks]     # debug bellows: 1kg of air a tick into a permeable tile. Mints matter
 *                            # and admits it — `airBalance` stays 0, `injectedAir` is the admission
 * water <x> <y> [ticks]      # the same, but liquid water — ~11kg a tick, arriving at 230K because
 *                            # this model boils water near -33C (PLAN_phase_transitions.md 5c)
 * overlay <name>             # PLAIN/HEAT/AIR/PRESSURE/DENSITY/FLOW — what `shot` draws through
 * camera fit|centre <x> <y>|zoom <tilePx>|pan <dx> <dy>
 * field <what> [x0 y0 x1 y1] # ASCII map: pressure|density|speed|heat|air|flow|build|
 *                            # species:<Name> — the only view that can show one gas settling
 * probe <x> <y>              # everything known about one tile, in full
 * landmarks                  # print all landmark names and their current (x, y) coordinates
 * trend <samples> <ticks>    # run and tabulate the conserved totals — the drift/blow-up detector
 * state [name] | shot [name] # JSON totals / PNG capture, both written to outDir
 * expect <field> <op> <value># op is = < > ; non-zero exit if any fail
 * echo <text>
 *
 * Coordinates accept a landmark-relative syntax so scripts survive vessel refits:
 *   <n>              — absolute tile index (plain integer, existing behaviour)
 *   <landmark>       — anchor tile of a machine kind (e.g. `extractor`), or `origin`
 *   <landmark>+<n>   — anchor offset in this axis by +n (e.g. `extractor+3`)
 *   <landmark>-<n>   — anchor offset in this axis by −n
 *   `origin` is the minimum corner of every placed machine; the others are the first tile of
 *   that machine kind in row-major order. Note `hull` names the first HULL tile, not the corner.
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
                // A command that throws is a failure, not a note. It used to be printed and
                // swallowed, which let `probe 48 30` address a tile that does not exist, say so,
                // and still exit 0 — so a script could be repaired, still be wrong, and report
                // success. The run continues after one, because seeing all of a script's damage in
                // a single pass is worth more than stopping at the first, but the exit code below
                // now counts them.
                runCatching { h.exec(line) }.onFailure {
                    println("! error on '$line': ${it.message}")
                    h.failures.add("error on '$line': ${it.message}")
                }
            }
        } finally {
            h.cleanup()
        }
        println("[agent] done -> ${outDir.absolutePath}")
        if (h.failures.isNotEmpty()) {
            println("[agent] ${h.failures.size} failure(s):")
            for (f in h.failures) println("[agent]   - $f")
            throw IllegalStateException("${h.failures.size} failure(s)")
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
                "new" -> {
                    controller.reset(starterVessel(controller.cfg.initialGrid))
                    println("[agent] new world, tick ${controller.tick}, ${state.bodies.size} bodies adrift")
                }
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
                "remove" -> {
                    // Optional third argument names the layer, so a script can take the pipes out of
                    // a room and leave the deck — the delete tool's aim, driven from a script.
                    val layer = t.getOrNull(3)?.let { name ->
                        DeleteLayer.entries.firstOrNull { it.name.equals(name, true) }
                            ?: error("unknown layer '$name' (have ${DeleteLayer.entries.map { it.label }})")
                    } ?: DeleteLayer.Top
                    controller.removeAt(index(t[1], t[2]), layer)
                    settle()
                }
                // One tick of the debug bellows per `inject`, which is what holding the button for
                // one tick does. `inject <x> <y> [ticks]` for a longer breath.
                "inject" -> {
                    val at = index(t[1], t[2])
                    val ticks = t.getOrNull(3)?.toIntOrNull() ?: 1
                    repeat(ticks) {
                        controller.injectTile = at
                        controller.stepOnce()
                    }
                    controller.injectTile = TileIndex.NONE
                    println("[agent] injected ${ticks} tick(s) at (${t[1]},${t[2]}) — " +
                        "${fmt(grams(state.injectedAirMass))}g admitted, airBalance ${fmt(grams(state.airBalance))}")
                }
                // The water injector, same shape as `inject` but a liquid — the only way to get one
                // into the world. Arrives cold on purpose; see Edit.WATER_INJECT_KELVIN.
                "water" -> {
                    val at = index(t[1], t[2])
                    val ticks = t.getOrNull(3)?.toIntOrNull() ?: 1
                    val was = controller.tool
                    controller.tool = Tool.InjectWater
                    repeat(ticks) {
                        controller.injectTile = at
                        controller.stepOnce()
                    }
                    controller.injectTile = TileIndex.NONE
                    controller.tool = was
                    println("[agent] watered ${ticks} tick(s) at (${t[1]},${t[2]}) — " +
                        "${fmt(grams(state.injectedAirMass))}g admitted, airBalance ${fmt(grams(state.airBalance))}")
                }
                "rotate" -> { controller.rotate(index(t[1], t[2])); settle() }
                // `wire <x> <y> <ALWAYS|WIRE> <permille>` — appends one RUN term, which is the whole
                // of the wiring grammar a script has ever needed. Without it an airlock cannot be
                // opened headlessly at all: it ships wired to nothing on purpose, so every screenshot
                // of one would be of a shut door.
                "wire" -> {
                    val at = index(t[1], t[2])
                    val source = SignalSource.ALL.firstOrNull { it.name.equals(t[3], true) }
                        ?: error("unknown source '${t[3]}' (have ${SignalSource.ALL.map { it.label }})")
                    val slot = controller.state.machineCovering(at)?.wiring?.triggers(Action.Run)?.size ?: 0
                    controller.wire(at, Action.Run, slot, Trigger(source, t[4].toInt()))
                    settle()
                    println("[agent] wire ${t[1]},${t[2]} RUN += ${source.label}@${t[4]}")
                }
                // `bind <x> <y> <key>` and `hold <key>...` / `release` — the pilot's hands. Without
                // these no script can press anything, and the flight loop is unreachable headlessly.
                "bind" -> {
                    val at = index(t[1], t[2])
                    val want = InputKey.ALL.firstOrNull { it.name.equals(t[3], true) }
                        ?: error("unknown key '${t[3]}' (have ${InputKey.ALL.map { it.label }})")
                    val current = controller.state.machineCovering(at)
                    require(current is WireButton) { "no button at ${t[1]},${t[2]}" }
                    controller.bindKey(at, want)
                    settle()
                    println("[agent] bind ${t[1]},${t[2]} -> ${want.label}")
                }
                "hold" -> {
                    controller.mode = Mode.Flight
                    var mask = 0
                    for (name in t.drop(1)) {
                        mask = mask or (InputKey.ALL.firstOrNull { it.name.equals(name, true) }
                            ?: error("unknown key '$name'")).bit
                    }
                    controller.heldKeys = mask
                    settle()
                    println("[agent] holding ${t.drop(1).joinToString(" ")}")
                }
                "release" -> {
                    controller.heldKeys = 0
                    settle()
                    println("[agent] released")
                }
                // What a left-click means, and what the bottom-left panel is therefore showing.
                // Presentation only — every command here drives the controller directly — but a
                // screenshot of the delete panel is not reachable any other way.
                "tool" -> {
                    controller.tool = Tool.entries.firstOrNull { it.name.equals(t[1], true) }
                        ?: error("unknown tool '${t[1]}' (have ${Tool.entries.map { it.label }})")
                    t.getOrNull(2)?.let { name ->
                        controller.deleteLayer = DeleteLayer.entries.firstOrNull { it.name.equals(name, true) }
                            ?: error("unknown layer '$name'")
                    }
                    println("[agent] tool -> ${controller.tool.label} (${controller.deleteLayer.label})")
                }
                "overlay" -> {
                    overlay = Overlay.entries.firstOrNull { it.name.equals(t[1], true) || it.label.equals(t[1], true) }
                        ?: error("unknown overlay '${t[1]}' (have ${Overlay.entries.map { it.label }})")
                    println("[agent] overlay -> ${overlay.label}")
                }
                // `thrust <dx> <dy> [ticks]` — hold the debug engine, run, let go. The same held
                // state the arrow keys drive, so a script burns exactly as a player does rather
                // than through a back door that could drift from what the game actually does.
                "thrust" -> {
                    controller.thrustX = t[1].toInt()
                    controller.thrustY = t[2].toInt()
                    val n = t.getOrNull(3)?.toInt() ?: 1
                    repeat(n) { controller.stepOnce() }
                    controller.thrustX = 0
                    controller.thrustY = 0
                    println("[agent] burned ${t[1]},${t[2]} for $n ticks -> tick ${controller.tick}")
                }
                // `gravity <x> <y>` in g, as thousandths — the plating dial, not the reading. Worth a
                // command of its own after §5e: the sim spent its whole life at exactly one g and had
                // a truncation cliff sitting at that value, so being able to say "run this at nought
                // g" from a script is the cheapest guard against the next one.
                "gravity" -> {
                    val gx = (t[1].toDouble() * Int.MAX_VALUE).toLong()
                    val gy = (t[2].toDouble() * Int.MAX_VALUE).toLong()
                    controller.reset(state.copy(gravity = Frac2(Frac(gx), Frac(gy))))
                    println("[agent] plating gravity -> ${t[1]}, ${t[2]} g")
                }
                // `rock <x> <y> [radius]` — the stand-in for capture until H4, same edit the F6 key
                // queues, so a script and a player put a rock in the world the same way.
                "rock" -> {
                    val (ix, iy) = coordinates(t[1], t[2])
                    controller.dropRock(ix.toFloat(), iy.toFloat())
                    settle()
                    println("[agent] body at (${t[1]},${t[2]}) — ${state.bodies.size} adrift")
                }
                // `fit` — the grid back to the ship plus its pad, the same edit F8 queues. The only
                // command here that can make the grid smaller.
                "fit" -> {
                    controller.fit()
                    settle()
                    println("[agent] fit -> ${state.grid.width}x${state.grid.height}")
                }
                "camera" -> camera(t)
                "field" -> field(t[1], t.drop(2).map { it.toInt() })
                "probe" -> probe(index(t[1], t[2]))
                "landmarks" -> printLandmarks()
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

        private fun coordinates(x: String, y: String): Pair<Int, Int> {
            val grid = state.grid
            val ix = parseCoord(x, grid, isXAxis = true)
            val iy = parseCoord(y, grid, isXAxis = false)
            return ix to iy
        }

        private fun index(x: String, y: String): TileIndex {
            val (ix, iy) = coordinates(x, y)
            val grid = state.grid
            require(grid.inBounds(ix, iy)) { "($ix,$iy) is outside the ${grid.width}x${grid.height} grid" }
            return grid.tile(ix, iy)
        }

        private fun parseCoord(token: String, grid: Grid, isXAxis: Boolean): Int {
            token.toIntOrNull()?.let { return it }

            val landmarkMatch = Regex("^(.+?)([+-])(\\d+)$").find(token)
            if (landmarkMatch != null) {
                val name = landmarkMatch.groupValues[1].lowercase()
                val sign = if (landmarkMatch.groupValues[2] == "+") 1 else -1
                val offset = landmarkMatch.groupValues[3].toInt() * sign
                return resolveLandmark(name, grid, isXAxis) + offset
            }

            val name = token.lowercase()
            return resolveLandmark(name, grid, isXAxis)
        }

        /**
         * A landmark is **any deck machine kind**, plus `origin` for the minimum corner of
         * everything placed. Derived from [DeckMachineKind] rather than listed, so a new machine becomes
         * a landmark by existing — a hand-kept list and the kinds it names drift apart silently, and
         * the drift shows up as a script addressing a landmark the harness has never heard of.
         *
         * ⚠️ The corner is `origin` and not `hull` because `Hull` is itself a machine kind: one name
         * would have meant both "the first hull tile" and "the corner of the bounding box", which
         * are different tiles the moment a ship is not a rectangle.
         */
        private fun landmarkKind(name: String): DeckMachineKind? =
            DeckMachineKind.ALL.firstOrNull { it.name.equals(name, true) }

        private fun landmarkNames(): List<String> =
            (DeckMachineKind.ALL.map { it.name.lowercase() } + "origin").sorted()

        /** The minimum corner of every placed machine, or null if the world is empty. */
        private fun hullCorner(grid: Grid): Pair<Int, Int>? {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            for (tile in grid.tiles) {
                if (state[tile] == null && state.deck[tile] == null) continue
                val x = grid.xOf(tile)
                val y = grid.yOf(tile)
                if (x < minX) minX = x
                if (y < minY) minY = y
            }
            return if (minX == Int.MAX_VALUE) null else minX to minY
        }

        /** The first tile holding a machine of this kind, in row-major order, or null. */
        private fun anchorOf(kind: DeckMachineKind): TileIndex? =
            state.grid.tiles.firstOrNull { (state.deck[it])?.kind == kind }

        private fun resolveLandmark(name: String, grid: Grid, isXAxis: Boolean): Int {
            if (name == "origin") {
                val corner = hullCorner(grid) ?: error("no machines in the world, so 'origin' has no corner")
                return if (isXAxis) corner.first else corner.second
            }
            val kind = landmarkKind(name) ?: error("unknown landmark '$name' (have ${landmarkNames()})")
            val at = anchorOf(kind) ?: error("no ${kind.label} in the world, so '$name' names nothing")
            return if (isXAxis) grid.xOf(at) else grid.yOf(at)
        }

        private fun printLandmarks() {
            val grid = state.grid
            // Exactly the set a coordinate may name — printing anything else would advertise a
            // landmark that fails when used.
            val found = DeckMachineKind.ALL.mapNotNull { kind ->
                anchorOf(kind)?.let { "${kind.name.lowercase()} (${grid.xOf(it)},${grid.yOf(it)})" }
            } + listOfNotNull(hullCorner(grid)?.let { "origin (${it.first},${it.second})" })

            if (found.isEmpty()) println("[agent] landmarks: nothing placed")
            else println("[agent] landmarks: ${found.joinToString(" | ")}")
        }

        /** A brush by name — a conduit or a building, since the build menu no longer tells them apart. */
        private fun kind(name: String): Brush =
            Brush.ALL.firstOrNull { it.label.equals(name, true) }
                ?: Brush.ALL.firstOrNull { b ->
                    when (b) {
                        is Brush.Run -> b.conduit.name.equals(name, true)
                        is Brush.Building -> b.kind.name.equals(name, true)
                    }
                }
                ?: error("unknown brush '$name' (have ${Brush.ALL.map { it.label }})")

        private fun direction(name: String): Direction =
            Direction.entries.firstOrNull { it.name.equals(name, true) }
                ?: error("unknown direction '$name' (have ${Direction.entries.map { it.name }})")

        private fun machineCount(): Int = state.grid.tiles.count { state.deck[it] != null }

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
                else println("[agent]   . deck  # machine  = rail  B bridge  H hull")
                return
            }

            val value: (TileIndex) -> Double = when (what.lowercase()) {
                "pressure" -> { tile -> state.air.pressureAt(tile).toDouble() }
                "density" -> { tile -> state.air.densityAt(tile).toDouble() }
                "speed" -> { tile -> flow.speedAt(tile).toDouble() }
                "heat", "temp" -> { tile -> state.kelvinAt(tile).toDouble() }
                // The air's temperature, which is a different number from the fabric's until
                // conduction couples the two -- and the one the fluid actually acts on.
                "airtemp" -> { tile -> state.airKelvinAt(tile).toDouble() }
                "air", "mass" -> { tile -> grams(state.air.mixtureAt(tile).total) }
                // The pipes, which are a second fluid field on the same lattice and so map exactly
                // like the room air. Worth having as its own view rather than folded into `air`: the
                // whole question about a pipe is whether what is in it is in the PIPE, and a
                // combined map cannot answer that.
                "pipe" -> { tile -> grams(state.pipeAir.mixtureAt(tile).total) }
                "pipetemp" -> { tile -> state.pipeAir.kelvinAt(tile).toDouble() }
                "pipepressure" -> { tile -> state.pipeAir.pressureAt(tile).toDouble() }
                // One gas on its own. Bulk flow provably cannot mix or unmix, so the question
                // "has the carbon dioxide settled?" is not answerable from `density` or `air`,
                // which show the mixture — only from the species' own map.
                else -> if (what.startsWith("species:", true)) {
                    val name = what.substringAfter(':')
                    val sp = Species.ALL.firstOrNull { it.name.equals(name, true) }
                        ?: error("unknown species '$name' (have ${Species.ALL.map { it.name }})")
                    ({ tile: TileIndex -> grams(state.air.massOf(tile, sp)) })
                } else error(
                    "field pressure|density|speed|heat|airtemp|air|pipe|pipetemp|pipepressure|" +
                        "species:<Name>|flow|build"
                )
            }

            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (y in y0..y1) for (x in x0..x1) {
                val v = value(grid.tile(x, y))
                lo = min(lo, v); hi = max(hi, v)
            }
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            printGrid(what, x0, y0, x1, y1) { tile ->
                RAMP[((value(tile) - lo) / span * (RAMP.length - 1)).roundToInt().coerceIn(0, RAMP.length - 1)]
            }
            println("[agent]   '${RAMP.first()}' = ${fmt(lo)}   '${RAMP.last()}' = ${fmt(hi)}   (linear)")
        }

        /** For `air`, the dominant species is more use than the total — that is what "which gas" means. */
        private fun printGrid(what: String, x0: Int, y0: Int, x1: Int, y1: Int, glyph: (TileIndex) -> Char) {
            val grid = state.grid
            println("[agent] field $what  x $x0..$x1  y $y0..$y1  tick ${controller.tick}")
            // A ruler every ten columns, so a tile can be located without counting.
            val head = StringBuilder("      ")
            for (x in x0..x1) head.append(if (x % 10 == 0) ((x / 10) % 10).digitToChar() else ' ')
            println(head)
            for (y in y0..y1) {
                val row = StringBuilder()
                row.append("%4d  ".format(y))
                for (x in x0..x1) row.append(glyph(grid.tile(x, y)))
                println(row)
            }
        }

        private fun buildGlyph(tile: TileIndex): Char {
            if (state.machineCovering(tile) is Bridge) return 'B'
            state.railAt(tile)?.let { return '=' }
            val m = state.machineCovering(tile)
            if (m != null) return if (m::class.simpleName == "Hull") 'H' else '#'
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
            for (tile in grid.tiles) {
                if (state[tile] == null
                    && state.deck[tile] == null
                    && state.railAt(tile) == null) continue
                val x = grid.xOf(tile); val y = grid.yOf(tile)
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
        private fun probe(tile: TileIndex) {
            val grid = state.grid
            val x = grid.xOf(tile); val y = grid.yOf(tile)
            val air = state.air.mixtureAt(tile)
            println("[agent] probe ($x,$y) tile $tile @ tick ${controller.tick}")
            println("[agent]   machine   ${state.machineCovering(tile)?.let { it::class.simpleName } ?: "-"}" +
                "  rail ${state.railAt(tile)?.let { "yes held=${!state.rail.isEmpty(tile)}" } ?: "-"}" +
                "  bridge ${if (state.machineCovering(tile) is Bridge) "yes" else "-"}")
            println("[agent]   heat      ${state.kelvinAt(tile)}K  air ${state.airKelvinAt(tile)}K")
            println("[agent]   pressure  ${state.air.pressureAt(tile)} mmol")
            println("[agent]   density   ${state.air.densityAt(tile)}")
            println("[agent]   air       ${fmt(grams(air.total))}g  ${composition(air)}")
            println("[agent]   flow      x=${state.flow.xAt(tile)}g/t y=${state.flow.yAt(tile)}g/t " +
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
            println("[agent] trend: %8s %12s %10s %12s %10s".format(
                "tick", "airMass", "dAir", "storedJ", "peakSpd"))
            var lastAir = state.atmosphereMass
            repeat(samples) {
                repeat(ticksEach) { controller.stepOnce() }
                val air = state.atmosphereMass
                println("[agent]        %8d %12.3f %10.3f %12.3f %10.5f".format(
                    controller.tick, grams(air), grams(air - lastAir), joules(state.storedEnergy),
                    state.flow.peakSpeed()))
                lastAir = air
            }
            println("[agent]   baseline air ${fmt(grams(state.baselineAirMass))}g, " +
                "vented ${fmt(grams(state.airVentedMass))}g " +
                "(balance ${fmt(grams(state.airBalance))}g)")
        }

        // ── observations ─────────────────────────────────────────────────────────────

        /**
         * The sim's mass unit read out in grams — the harness's half of the same rule the HUD
         * follows (see `OutofspaceHud.mass`): a script states thresholds in the units a person
         * weighs things in, and nothing outside [Budget] needs to know what one integer currently
         * means. Move [Budget.MICROGRAMS_PER_UNIT] and every script below stays correct.
         *
         * A `Double` divide rather than an integer one, and that is the whole trick: a one-unit
         * leak reads as 1e-6 rather than floored to 0, so `expect massBalance = 0` — which compares
         * exactly — stays exactly as sharp a tripwire as it was in raw units. Integer division here
         * would blind every conservation assertion in the suite to sub-gram drift.
         */
        private fun grams(units: Long): Double = units.toDouble() / Budget.GRAM

        /** Energy in joules, the twin of [grams]; see its note for the `Double` and the tripwires. */
        private fun joules(units: Long): Double = units.toDouble() / Budget.JOULE

        private fun reading(field: String): Double? = when (field) {
            "tick" -> controller.tick.toDouble()
            "machines" -> machineCount().toDouble()
            "gridWidth" -> state.grid.width.toDouble()
            "gridHeight" -> state.grid.height.toDouble()
            "originX" -> state.positionX.toDouble() / Flight.PER_TILE
            "originY" -> state.positionY.toDouble() / Flight.PER_TILE
            // Rooms and pipes together, because they share one ledger and `airBalance` below is
            // that ledger. `pipeMass` separates them for a script that cares which side gas is on.
            "airMass" -> grams(state.atmosphereMass)
            "pipeMass" -> grams(state.pipeAir.totalMass)
            "airVented" -> grams(state.airVentedMass)
            // The flight loop's own number: what the gas leaving has pushed the ship by. Note it
            // counts the *reaction*, so venting to starboard makes this negative.
            "impulseX" -> grams(state.vesselImpulseX)
            "impulseY" -> grams(state.vesselImpulseY)
            "injectedAir" -> grams(state.injectedAirMass)
            "airBalance" -> grams(state.airBalance)
            "extractedMass" -> grams(state.extractedMass)
            "ventedMass" -> grams(state.ventedMass)
            "inTransitMass" -> grams(state.inTransitMass)
            "stockpileMass" -> grams(state.stockpile.totalMass)
            "storedEnergy" -> joules(state.storedEnergy)
            "generatedEnergy" -> joules(state.generatedEnergy)
            "radiatedEnergy" -> joules(state.radiatedEnergy)
            "solidToAirEnergy" -> joules(state.solidToAirEnergy)
            // The whole solid balance as one number, so a script can `expect heatBalance == 0`
            // rather than reassembling five terms. Zero, always — see [VesselState.baselineEnergy].
            "heatBalance" -> joules(
                state.storedEnergy + state.radiatedEnergy + state.solidToAirEnergy -
                    state.generatedEnergy - state.acquiredEnergy - state.insertedEnergy - state.baselineEnergy
            )
            "airHeatBalance" -> joules(state.airEnergyBalance)
            // The ore ledger as one number, the twin of `airBalance` and `heatBalance`. Zero, always
            // -- and the right thing for a script to assert, since `extractedMass` on its own is a fact
            // about how long the starter vessel's extractor has been running.
            "massBalance" -> grams(state.inTransitMass + state.ventedMass + state.builtMass - state.extractedMass)
            // Body stats. No conservation ledger — bodies spawn/despawn freely (RockSpawner).
            "rockCount" -> state.bodies.size.toDouble()
            // The first body, in tiles, so a script can say where it went and how fast. Zero when
            // there is none, which reads as "nothing out there" rather than failing the lookup.
            //
            // ⚠️ Two frames, and the readouts inherit them: `rockX/rockY` are on the **grid** — which
            // tile it is over — and `rockVX/rockVY` are through the **world**. A body at rest reads
            // as zero velocity while its position walks astern of a burning ship, and that is the
            // model being honest rather than the instrument disagreeing with itself. See [RigidBody].
            "rockX" -> (state.bodies.firstOrNull()?.centreX ?: 0L).toDouble() / Flight.PER_TILE
            "rockY" -> (state.bodies.firstOrNull()?.centreY ?: 0L).toDouble() / Flight.PER_TILE
            "rockVX" -> (state.bodies.firstOrNull()?.velocityX ?: 0L).toDouble() / Flight.PER_TILE
            "rockVY" -> (state.bodies.firstOrNull()?.velocityY ?: 0L).toDouble() / Flight.PER_TILE
            "hottestSolidK" -> (state.bodies.maxOfOrNull { it.kelvin } ?: 0).toDouble()
            "hottestAirK" -> (state.grid.tiles).maxOf { state.airKelvinAt(it) }.toDouble()
            "peakSpeed" -> state.flow.peakSpeed().toDouble()
            "impulseX" -> grams(state.vesselImpulseX)
            "impulseY" -> grams(state.vesselImpulseY)
            // The two instruments the momentum ledger is watched with. `undelivered` is the part of
            // the solve that had nowhere to go, and it is expected to grow under acceleration; the
            // whole ledger as one number is `momentumBalance`, which is zero or something is wrong.
            "undeliveredX" -> grams(state.undeliveredImpulseX)
            "undeliveredY" -> grams(state.undeliveredImpulseY)
            // The debug engine's cumulative cheating, which is subtracted rather than ignored: the
            // identity has a fifth store now, and it reduces to the old one whenever nothing has
            // fired. See [VesselState.debugImpulseX] for why a shortcut that did not book this would
            // cost the instrument rather than the physics.
            "debugImpulseX" -> grams(state.debugImpulseX)
            "debugImpulseY" -> grams(state.debugImpulseY)
            // Momentum that is now in the bodies, because the hull hit them. A store rather than an
            // apology: `+J` to the body and `−J` to the ship conserve by construction, and this term
            // is here because only the ship's half is inside the ledger. See [VesselState.bodyImpulseX].
            "rockImpulseX" -> grams(state.bodyImpulseX)
            "rockImpulseY" -> grams(state.bodyImpulseY)
            "momentumBalance" -> grams(state.momentumBalanceX + state.momentumBalanceY)
            // Flight, in tiles rather than in the sim's billionths, so a script can say what it means.
            "mass" -> grams(state.mass)
            "thrustX" -> grams(state.netImpulseX)
            "thrustY" -> grams(state.netImpulseY)
            "velocityX" -> state.velocityX.toDouble() / Flight.PER_TILE
            "velocityY" -> state.velocityY.toDouble() / Flight.PER_TILE
            "positionX" -> state.positionX.toDouble() / Flight.PER_TILE
            "positionY" -> state.positionY.toDouble() / Flight.PER_TILE
            // What anything loose aboard falls toward, in g. One is the plating; the rest is engine.
            "gravityX" -> state.feltGravity.x.raw.toDouble() / Int.MAX_VALUE
            "gravityY" -> state.feltGravity.y.raw.toDouble() / Int.MAX_VALUE
            else -> null
        }

        private val FIELDS = listOf(
            "tick", "machines", "gridWidth", "gridHeight", "originX", "originY",
            "airMass", "pipeMass", "airVented", "airBalance", "extractedMass",
            "ventedMass", "inTransitMass", "stockpileMass", "storedEnergy", "generatedEnergy",
            "radiatedEnergy", "solidToAirEnergy", "heatBalance", "airHeatBalance",
            "massBalance", "rockCount", "rockMass",
            "rockX", "rockY", "rockVX", "rockVY",
            "hottestSolidK", "hottestAirK", "peakSpeed", "impulseX", "impulseY",
            "undeliveredX", "undeliveredY", "debugImpulseX", "debugImpulseY",
            "rockImpulseX", "rockImpulseY", "momentumBalance",
            "mass", "thrustX", "thrustY", "velocityX", "velocityY", "positionX", "positionY",
            "gravityX", "gravityY",
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
            r.draw(state, TileIndex.NONE, overlay, 1f, 1f, controller.mode.camera)
            h.build(u, controller, fps = 0f, hovered = TileIndex.NONE)
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
