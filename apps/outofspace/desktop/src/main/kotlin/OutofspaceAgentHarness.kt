package org.emerge.desktop

import org.emerge.demo.outofspace.world.Conduit
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
import org.emerge.demo.outofspace.InspectLayer
import org.emerge.demo.outofspace.inspectableLayers
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.railFlow
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
 * run <ticks>                # advance exactly N ticks of the world. Ignores `pause` by design
 * pause on|off               # stop the world without stopping the loop — see `frames`
 * frames <n> [hz]            # N frames of real time, as a window would. The only way to watch a
 *                            # paused world settle: the clock runs, the passes do not
 * brush <kind> [dir]         # RAIL/EXTRACTOR/PROCESSOR/VENT/... and Right|Down|Left|Up
 * place <x> <y>              # build with the current brush
 * fit                        # shrink grid back to ship + pad
 * drag <x0> <y0> <x1> <y1>   # lay a conduit run — track connects by being DRAWN, so this is not
 *                            # the same as placing each tile
 * remove <x> <y> [layer]    # layer = TOP|BRIDGE|RAIL|PIPE|DECK|ALL (default TOP, one layer/click)
 * cancel <x> <y>            # calls off a deconstruction on every layer of the tile
 * rotate <x> <y>
 * wire <x> <y> <channel> <permille>  # append one RUN term. ALWAYS@1000 is "hold the button down",
 *                            # which is how a script opens an airlock — they ship wired to nothing
 * inject <x> <y> [ticks]     # debug bellows: 1kg of air a tick into a permeable tile. Mints matter
 *                            # and admits it — `airBalance` stays 0, `injectedAir` is the admission
 * water <x> <y> [ticks]      # the same, but liquid water — ~11kg a tick, arriving at 230K because
 *                            # this model boils water near -33C (PLAN_phase_transitions.md 5c)
 * wiki <Species> | wiki off      # open the reference on a species — also what narrows the nav map
 *                            # to it, so this is how a prospecting map gets photographed
 * overlay <name>             # PLAIN/HEAT/AIR/PRESSURE/DENSITY/FLOW — what `shot` draws through
 * camera fit|centre <x> <y>|zoom <tilePx>|pan <dx> <dy>
 * field <what> [x0 y0 x1 y1] # ASCII map: pressure|density|speed|heat|air|flow|build|
 *                            # species:<Name> — the only view that can show one gas settling
 * probe <x> <y>              # everything known about one tile, in full
 * landmarks                  # print all landmark names and their current (x, y) coordinates
 * trend <samples> <ticks>    # run and tabulate the conserved totals — the drift/blow-up detector
 * state [name] | shot [name] [live]  # JSON totals / PNG capture, both written to outDir. `live`
 *                            # draws on the world's clock rather than settled — the only way to
 *                            # photograph a packet mid-slide or an overlay mid-fade
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
                // `pause on|off` — stops the world without stopping the loop. A paused game runs
                // *frozen* ticks (see `OutofspaceReducer.freeze`): the clock advances, edits land,
                // and nothing else happens. Paired with `frames` below, this is how a script gets at
                // a stopped world at all — `run` steps live ticks and ignores the pause entirely,
                // deliberately, since every existing script means "advance the world" by it.
                "pause" -> {
                    controller.paused = t.getOrNull(1) != "off"
                    println("[agent] paused -> ${controller.paused}")
                }
                // `frames <n> [hz]` — n frames of *real time* through the same call a window makes,
                // rather than n ticks. The only clock that can show a paused world settling: what
                // makes a half-drawn slide finish is wall time reaching the controller while the
                // passes decline to run, and `run` cannot express that.
                "frames" -> {
                    val n = t[1].toInt()
                    val hz = t.getOrNull(2)?.toFloat() ?: 60f
                    repeat(n) { controller.tick(1f / hz) }
                    println("[agent] $n frames at ${hz}Hz -> tick ${controller.tick} (lived ${controller.livedTicks})")
                }
                // Turn the free-build privilege off, so a drawn run arrives as ghosts and has to be
                // paid for out of the tank. The switch is a world setting with no UI -- see
                // [VesselState.creative] and `apps/outofspace/PLAN_self_building_rails.md`.
                "creative" -> {
                    controller.reset(state.copy(creative = t.getOrNull(1) != "0"))
                    println("[agent] creative -> ${state.creative}")
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
                // The build drag's exact opposite: the edges the stroke draws become anti-edges on
                // one conduit, and nothing is taken up. A single tile draws no edge and so cuts
                // nothing — `cut x y` alone is a no-op, and every real cut names both ends.
                "cut" -> {
                    controller.tool = Tool.Cut
                    t.getOrNull(5)?.let { name ->
                        controller.cutConduit = Tool.CUTTABLE.firstOrNull { it.name.equals(name, true) }
                            ?: error("cannot cut '$name' (have ${Tool.CUTTABLE.map { it.label }})")
                    }
                    controller.apply(index(t[1], t[2]))
                    if (t.size > 4) controller.dragTo(index(t[3], t[4]))
                    controller.endDrag()
                    settle()
                    println("[agent] cut ${controller.cutConduit.label} (${t[1]},${t[2]})" +
                        if (t.size > 4) " -> (${t[3]},${t[4]})" else "")
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
                // Calls off a deconstruction on every layer of a tile — the mirror of `remove`, and
                // blind in the same way.
                "cancel" -> {
                    controller.cancelAt(index(t[1], t[2]))
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
                // Selection is a *view* state, not an edit — but several panels only exist for the
                // selected tile (the storage lock, the decomposer's dials), so without this the
                // harness can drive those machines and never photograph their controls.
                "select" -> {
                    controller.select(if (t.size < 3) TileIndex.NONE else index(t[1], t[2]))
                    settle()
                    println("[agent] selected -> ${controller.selected}")
                }

                // `inspect <x> <y> [layer]` — the player's own gesture, which is the only way to
                // photograph a machine's settings now that they live on one layer of one panel.
                // With no layer named it clicks: first the topmost readable layer, then the next.
                "inspect" -> {
                    val at = index(t[1], t[2])
                    if (t.size > 3) {
                        val want = InspectLayer.entries.firstOrNull { it.name.equals(t[3], true) || it.label.equals(t[3], true) }
                            ?: error("unknown layer '${t[3]}' (have ${InspectLayer.entries.map { it.label }})")
                        controller.inspect(at, want)
                    } else {
                        controller.inspect(at)
                    }
                    settle()
                    println("[agent] inspecting (${t[1]},${t[2]}) ${controller.inspectLayer.label} " +
                        "of ${inspectableLayers(state, at).map { it.label }}")
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
                // `sas on|off` — the vessel-wide autopilot, which is a switch and not a key.
                "sas" -> {
                    if ((t.getOrNull(1) != "off") != state.sas) controller.toggleSas()
                    settle()
                    println("[agent] sas -> ${state.sas}")
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
                        if (controller.tool == Tool.Cut) {
                            controller.cutConduit = Tool.CUTTABLE.firstOrNull { it.name.equals(name, true) }
                                ?: error("cannot cut '$name'")
                        } else {
                            controller.deleteLayer = DeleteLayer.entries.firstOrNull { it.name.equals(name, true) }
                                ?: error("unknown layer '$name'")
                        }
                    }
                    println("[agent] tool -> ${controller.tool.label} " +
                        if (controller.tool == Tool.Cut) "(${controller.cutConduit.label})"
                        else "(${controller.deleteLayer.label})")
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
                // `wiki <Species>` | `wiki off` — open the reference on a species, which is also what
                // narrows the nav map to it (see `OutofspaceHud.navView`). Reachable by `tap` only
                // from whatever chip happens to be on screen, and a script that wants to photograph
                // the prospecting map should not have to build a rock into a panel first.
                "wiki" -> {
                    if (t.getOrNull(1)?.equals("off", true) != false) {
                        controller.closeWiki()
                        println("[agent] reference closed")
                    } else {
                        val species = Species.ALL.firstOrNull { it.name.equals(t[1], true) }
                            ?: error("unknown species '${t[1]}'")
                        controller.openWiki(species)
                        println("[agent] reference -> ${species.name}")
                    }
                }
                "camera" -> camera(t)
                "field" -> field(t[1], t.drop(2).map { it.toInt() })
                "probe" -> probe(index(t[1], t[2]))
                "landmarks" -> printLandmarks()
                "stalls" -> stalls()
                "flow" -> flowAt(index(t[1], t[2]))
                "trend" -> trend(t[1].toInt(), t[2].toInt())
                "state" -> dumpState(t.getOrElse(1) { "state" })
                // `shot <name> [live]` — `live` draws on the world's real clock instead of a
                // settled one, which is the only way to photograph anything mid-animation. See
                // [shot].
                "shot" -> shot(t.getOrElse(1) { "shot" }, live = t.getOrElse(2) { "" }.equals("live", true))
                // `tap <label>` — presses a HUD button by the text on it, which is the only way to
                // drive a control that exists purely in the panel layer (the reference panel's
                // species rows, its back button). It builds the HUD exactly as `shot` does and then
                // clicks what it finds, so a script that taps something can only pass if the button
                // is really reachable — the failure the storage lock shipped with.
                "tap" -> tapUi(line.removePrefix("tap").trim())
                // `scroll <area> <px>` — the wheel, over a scrollable list. The reference panel is
                // taller than the screen for anything busy, and a scroll area emits no click region
                // for a row it did not draw, so a row below the fold is unreachable to `tap` until
                // this has moved it into view. Exactly what the player's wheel does.
                "scroll" -> {
                    val (_, h, u) = ensureGl()
                    h.build(u, controller, fps = 0f, hovered = TileIndex.NONE)
                    u.scrollBy(t[1], t[2].toFloat())
                    settle()
                    println("[agent] scrolled '${t[1]}' to ${u.scrollOffsetOf(t[1])}")
                }
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
        /**
         * Every construction site still unfinished and every lump standing still, in one list.
         *
         * A stall is a *global* fact — a run jams because of something several tiles away and often
         * because of something on another run entirely — so hunting it a `probe` at a time means
         * knowing where to look, which is exactly what you do not know. This says where to look.
         *
         * Run the world on a bit and run it again: what is still here both times is the stall, and
         * what has moved is just traffic.
         */
        private fun stalls() {
            val grid = state.grid
            val ghosts = ArrayList<String>()
            val standing = ArrayList<String>()
            val marked = ArrayList<String>()
            for (tile in grid.tiles) {
                val x = grid.xOf(tile); val y = grid.yOf(tile)
                for (c in Conduit.entries) {
                    val seg = state.conduits.at(c, tile) ?: continue
                    if (state.conduits.isGhost(c, tile)) {
                        ghosts.add("($x,$y) ${c.label} ${state.conduits.tracks.builtPermille(c, tile) / 10}%")
                    }
                    if (seg.deconstructing) marked.add("($x,$y) ${c.label}")
                }
                val m = state.machineCovering(tile)
                if (m != null && m.center == tile && state.deck.isGhost(tile)) {
                    ghosts.add("($x,$y) ${m::class.simpleName} ${state.deck.builtPermille(m) / 10}%")
                }
                state.rail.resourceAt(tile)?.let {
                    // ⚠️ Capped. A lump of raw ore carries a trace of all 165 species and printing
                    // one in full buries the list this command exists to be.
                    standing.add("($x,$y) ${fmt(grams(it.total))}g ${composition(it, top = 3)}")
                }
            }
            println("[agent] stalls @ tick ${controller.tick}")
            println("[agent]   unfinished (${ghosts.size}): ${ghosts.joinToString("; ")}")
            println("[agent]   marked     (${marked.size}): ${marked.joinToString("; ")}")
            println("[agent]   standing   (${standing.size}): ${standing.joinToString("; ")}")
        }

        /**
         * Which way material may actually leave [tile], and its neighbours — the flow graph itself.
         *
         * ⚠️ **This is the one thing about a run that nothing else can tell you.** Track joins are
         * symmetric and `probe` shows them, but an *edge* carries material one way only, and which
         * way is decided by a walk over the whole network. Two tiles can be joined, both be part of
         * a perfectly sensible route, and still have their edge pointing the wrong way because some
         * other consumer claimed it first. Every "why will this not move" that survives `probe` is
         * this.
         *
         * Reads [railFlow], which is the derivation the reducer itself uses — a second one written
         * for the harness would be a second opinion about exactly the thing under suspicion.
         */
        private fun flowAt(tile: TileIndex) {
            val grid = state.grid
            val flow = state.railFlow()
            fun line(at: TileIndex): String {
                val x = grid.xOf(at); val y = grid.yOf(at)
                if (state.railAt(at) == null) return "($x,$y) no track"
                val out = Direction.entries.filter { flow.allows(at, it) }
                // ⚠️ **Where it falls in the walk order**, which the whitelist depends on utterly:
                // demand is carried upstream in one pass over it, so a tile that appears BEFORE
                // something it can send to is read before that thing's appetite is known, and comes
                // out looking like a dead end. `-` means it is not in the order at all.
                val order = flow.order.indexOf(at)
                return "($x,$y) " +
                    (if (at in flow.sinks) "SINK " else "") +
                    (if (out.isEmpty()) "sends NOWHERE" else "sends ${out.joinToString(" ") { it.name.uppercase() }}") +
                    "  order ${if (order < 0) "-" else order}" +
                    (if (!flow.isFed(at)) "  (not in the flow at all)" else "")
            }
            println("[agent] flow @ tick ${controller.tick}")
            println("[agent]   ${line(tile)}")
            for (dir in Direction.entries) {
                val next = grid.neighbour(tile, dir)
                if (next == TileIndex.NONE) continue
                println("[agent]   ${dir.name.lowercase().padEnd(5)} ${line(next)}")
            }
        }

        private fun probe(tile: TileIndex) {
            val grid = state.grid
            val x = grid.xOf(tile); val y = grid.yOf(tile)
            val air = state.air.mixtureAt(tile)
            println("[agent] probe ($x,$y) tile $tile @ tick ${controller.tick}")
            val standing = state.machineCovering(tile)
            println("[agent]   machine   ${standing?.let { it::class.simpleName } ?: "-"}" +
                "  rail ${state.railAt(tile)?.let { "yes held=${!state.rail.isEmpty(tile)}" } ?: "-"}" +
                "  bridge ${if (standing is Bridge) "yes" else "-"}")
            // The same two facts for the machine that a conduit reports below: how built it is, and
            // whether it has been told to go. A ghost machine and a marked one are the deck's halves
            // of the self-building loop — see `apps/outofspace/PLAN_self_building_rails.md`.
            if (standing != null) {
                println("[agent]   casing    ${state.deck.builtPermille(standing) / 10}% built" +
                    "  ${fmt(grams(standing.tiles(state.grid).sumOf { state.deck.stuff.massAt(it) }))}g" +
                    (if (standing.center in state.scrapping) "  MARKED FOR DECONSTRUCTION" else "") +
                    (if (state.deck.isGhost(standing.center)) "  GHOST" else ""))
            }
            // ⚠️ **Whose ports stand here, and which way they face.** A tile is a *source* to the
            // flow graph because some machine's OUTPUT port happens to sit on it, and that machine
            // can be a tile away with nothing on this tile to show for it. When a run refuses to
            // move the answer is very often a port nobody remembered was there.
            val portsHere = Conduit.entries.flatMap { c ->
                state.portsByTile(c)[tile].orEmpty().map { p ->
                    "${c.label}/${p.kind}" +
                        (state.deck[p.owner]?.let { " of ${it::class.simpleName}@(${grid.xOf(p.owner)},${grid.yOf(p.owner)})" } ?: "")
                }
            }
            if (portsHere.isNotEmpty()) println("[agent]   ports     ${portsHere.joinToString("  ")}")
            // ⚠️ **What is standing on the track, which is not what the track is made of.** The two
            // are separate layers and a residue problem is invisible in either one alone: a tile can
            // read as perfectly ordinary rail with a lump of something nothing wants parked on it.
            state.rail.resourceAt(tile)?.let {
                println("[agent]   load      ${fmt(grams(it.total))}g  ${composition(it)}")
            }
            println("[agent]   heat      ${state.kelvinAt(tile)}K  air ${state.airKelvinAt(tile)}K")
            println("[agent]   pressure  ${state.air.pressureAt(tile)} mmol")
            println("[agent]   density   ${state.air.densityAt(tile)}")
            println("[agent]   air       ${fmt(grams(air.total))}g  ${composition(air)}")
            println("[agent]   flow      x=${state.flow.xAt(tile)}g/t y=${state.flow.yAt(tile)}g/t " +
                "speed=${"%.5f".format(state.flow.speedAt(tile))} tiles/tick")
            // How built each conduit on this tile is, and whether it has been told to go — the two
            // facts a self-building run turns on. See `apps/outofspace/PLAN_self_building_rails.md`.
            for (c in Conduit.entries) {
                val seg = state.conduits.at(c, tile) ?: continue
                println("[agent]   ${c.label.lowercase().padEnd(9)} ${state.conduits.tracks.builtPermille(c, tile) / 10}% built" +
                    // ⚠️ **Exact, in the sim's own units, beside the human figure.** A conduit a
                    // microgram short of its bill prints as its full mass in grams and reads 99%
                    // for ever; the gap is the whole story and no gram-scale readout can show it.
                    "  ${fmt(grams(state.conduits.massAt(c, tile)))}g (${state.conduits.massAt(c, tile)}ug)" +
                    (if (seg.deconstructing) "  MARKED FOR DECONSTRUCTION" else "") +
                    (if (state.conduits.isGhost(c, tile)) "  GHOST" else ""))
                // ⚠️ **What it is made of, not merely how much.** A ghost bakes in whatever junk
                // came with the delivery that built it, and a length of track that came out
                // contaminated is refused by the next ghost down the line as something it cannot be
                // built from — so it can never hand its metal back. Invisible in a mass figure.
                println("[agent]   ${" ".repeat(9)} ${composition(state.conduits.tracks[c].mixtureAt(tile))}")
                // ⚠️ **What is standing on the tile, exactly.** `held` above says only that there is
                // something; the hunts that end here are about a residue too small to print as
                // grams — a lump of a few micrograms left in front of the material that would
                // finish a job is indistinguishable from an empty tile in every other readout, and
                // it is a permanent blockage because packets never merge.
                if (c == Conduit.Rail) {
                    state.rail.resourceAt(tile)?.let { lump ->
                        println("[agent]   ${" ".repeat(9)} load ${lump.total}ug  ${composition(lump)}")
                    }
                }
                // ⚠️ **Which way it is joined**, which is the one thing about a run you cannot see.
                // Conduit connects by being drawn, never by touching, so two tiles of track can sit
                // side by side and be no more connected than two tiles a metre apart — and the only
                // symptom is that nothing moves. Every "why will this not flow" hunt starts here.
                val joins = Direction.entries.filter { seg.linkedTo(it) }
                println("[agent]   ${" ".repeat(9)} joined ${if (joins.isEmpty()) "NOTHING" else joins.joinToString(" ") { it.name.uppercase() }}")
            }
        }

        private fun composition(m: org.emerge.demo.outofspace.chem.Mixture, top: Int = Int.MAX_VALUE): String {
            if (m.total == 0L) return "empty"
            val named = Species.ALL.filter { m[it] > 0L }.sortedByDescending { m[it] }
            val shown = named.take(top).joinToString("  ") { "${it.name} ${m[it] * 100 / m.total}%" }
            return if (named.size <= top) shown else "$shown  +${named.size - top} more"
        }

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
            // Grams that have stopped being cargo and become fabric: up while a ghost builds
            // itself, down while a marked segment hands its metal back.
            "builtMass" -> grams(state.builtMass)
            "massBalance" -> grams(state.inTransitMass + state.ventedMass + state.builtMass - state.extractedMass - state.baselineCargoMass)
            // The two terms `massBalance` is made of that had no readout of their own. Diagnosing a
            // drift means asking which term moved, and a field that is only ever *inside* a sum
            // cannot answer that — this was found while proving that 7.8 t of a "leak" on a real
            // save was the ship's own fabric.
            "baselineCargoMass" -> grams(state.baselineCargoMass)
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
            // The vented atmosphere's half of the boundary exchange — the twin of the exhaust
            // stores, and the only thing the air is allowed to do to the ship.
            "ventMomentumX" -> grams(state.ventMomentumX)
            "ventMomentumY" -> grams(state.ventMomentumY)
            "ventAngImpulse" -> grams(state.ventAngImpulse)
            // The atmosphere as a body in its own right: what it is carrying, and how fast that
            // makes it go compared with the hull towing it.
            "airMomentumX" -> grams(state.airMomentumX)
            "airMomentumY" -> grams(state.airMomentumY)
            "airAngImpulse" -> grams(state.airAngImpulse)
            "airVelocityX" -> if (state.air.totalMass <= 0L) 0.0
                else state.airMomentumX.toDouble() / state.air.totalMass * Flight.PER_TILE / Flight.PER_TILE
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
            // ⚠️ **Summed, so a break on x and an equal one on y read as zero.** Kept because
            // every script says `expect momentumBalance = 0`, but the axes are exposed beside it and
            // they are the sharper question — a coupling with a sign error one way round is exactly
            // the shape this hides.
            "momentumBalance" -> grams(state.momentumBalanceX + state.momentumBalanceY)
            "momentumBalanceX" -> grams(state.momentumBalanceX)
            "momentumBalanceY" -> grams(state.momentumBalanceY)
            // The angular half of flight, and there was no readout for any of it until a ship span
            // up in play and nothing in the harness could say how fast or which way. `spin` is the
            // one to watch: revolutions per second, signed clockwise, so a script can assert a ship
            // is holding attitude rather than merely assert it has not moved.
            "spin" -> state.angVel.toDouble() / (2.0 * Int.MAX_VALUE) * controller.cfg.ticksPerSecond
            "angImpulse" -> grams(state.angImpulse)
            // The angular ledger. Zero, or the ship has been spun by something that took no
            // reaction -- and it is RED today by design; see [VesselState.angularBalance].
            "angularBalance" -> grams(state.angularBalance)
            "exhaustAngImpulse" -> grams(state.exhaustAngImpulse)
            "bodyAngImpulse" -> grams(state.bodyAngImpulse)
            "netTorque" -> grams(state.netTorque)
            // Where the ship turns about and how reluctantly -- both in tiles, both moving, which is
            // the point: a torque booked about last tick's centre is booked about the wrong place.
            "comX" -> state.distribution.comX.toDouble() / Rotation.MILLI_TILE
            "comY" -> state.distribution.comY.toDouble() / Rotation.MILLI_TILE
            "gyration" -> isqrt(state.distribution.gyrationSq).toDouble() / 1000.0
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
            "ventedMass", "inTransitMass", "stockpileMass", "baselineCargoMass", "storedEnergy", "generatedEnergy",
            "radiatedEnergy", "solidToAirEnergy", "heatBalance", "airHeatBalance",
            "massBalance", "builtMass", "rockCount", "rockMass",
            "rockX", "rockY", "rockVX", "rockVY",
            "hottestSolidK", "hottestAirK", "peakSpeed", "impulseX", "impulseY",
            "ventMomentumX", "ventMomentumY", "ventAngImpulse",
            "airMomentumX", "airMomentumY", "airAngImpulse", "airVelocityX",
            "debugImpulseX", "debugImpulseY",
            "rockImpulseX", "rockImpulseY", "momentumBalance",
            "momentumBalanceX", "momentumBalanceY",
            "spin", "angImpulse", "netTorque", "angularBalance", "exhaustAngImpulse",
            "bodyAngImpulse", "comX", "comY", "gyration",
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

        /** Presses a HUD button by its label, against a freshly built panel tree. */
        private fun tapUi(label: String) {
            val (_, h, u) = ensureGl()
            h.build(u, controller, fps = 0f, hovered = TileIndex.NONE)
            val hit = u.tapLabel(label)
            if (hit) println("[agent] tapped '$label'")
            else {
                println("[agent] TAP FAIL no button labelled '$label'")
                failures.add("no button labelled '$label'")
            }
            settle()
        }

        /**
         * A PNG of the world as the desktop host would draw it.
         *
         * Settled by default: the tick has landed, and a capture must show where things **are**, not
         * an interpolated position corresponding to no state the sim was ever in. Every existing
         * script wants that, and every existing script gets it.
         *
         * ⚠️ **[live] is the exception, and it exists because of a bug nothing could photograph.**
         * A packet slides over the 32 ticks between one rail pass and the next, and drawing every
         * capture settled meant the harness could only ever see it parked. The interpolation was
         * wrong for months — snapping a fifth of a tile forward on arrival, teleporting a whole one
         * backwards mid-slide — with a green suite and a folder full of screenshots in which
         * nothing was moving. `live` draws on [OutofspaceController.simTime], the same clock a
         * frame on a real display uses, so `run` to a tick part-way through a span and the capture
         * shows the packet part-way along. No sub-tick fraction is involved or needed: the harness
         * advances whole ticks, and a whole tick part-way through a 32-tick span is a real frame.
         */
        private fun shot(name: String, live: Boolean = false) {
            val (r, h, u) = ensureGl()
            pendingCamera(r)
            glViewport(0, 0, RES_W, RES_H)
            val clock = if (live) controller.simTime else OutofspaceRenderer.SETTLED
            r.draw(state, TileIndex.NONE, InspectLayer.Deck, TileIndex.NONE, overlay, clock, controller.mode.camera)
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
