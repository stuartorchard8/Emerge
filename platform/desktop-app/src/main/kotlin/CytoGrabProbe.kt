package org.emerge.desktop

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Headless **drag-stability diagnostic** for the spring/grab solver. Builds a welded line of cells,
 * grabs one end, and orbits the grab target around the cluster (simulating the player dragging it),
 * logging per checkpoint the worst-case instability signals:
 *  - **maxSpeed** — fastest cell (logical units/tick),
 *  - **kinetic** — Σ½·mass·v² (energy injected by the grab/spring; should track the drag, not blow up),
 *  - **maxStretch** — worst spring |dist − rest| (logical units; springs whipping/overshooting),
 *  - **comLag** — how far the grabbed cell trails the target (a healthy mouse-joint follows closely).
 *
 * A smooth drag keeps these bounded and ~steady; spiking shows up as bursts in maxSpeed/kinetic/stretch.
 * `--args="<ticks> <printEvery> <cells> <orbitRadius logical> <orbitPeriod ticks>"`
 * (defaults 4000, 200, 5, 40, 400). A large radius / short period = a fast drag (the stress case).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 4000
    val every = args.getOrNull(1)?.toIntOrNull() ?: 200
    val nCells = args.getOrNull(2)?.toIntOrNull() ?: 5
    val radius = args.getOrNull(3)?.toFloatOrNull() ?: 40f
    val period = args.getOrNull(4)?.toIntOrNull() ?: 400

    val cfg = CytoConfig()
    val reducer = CytoReducer()

    // nCells > 0: a welded line with asymmetric masses (heavy grabbed cell + light tail — worst case
    // for the mass-ratio-weighted spring solver). nCells <= 0: a real grown colony from the default
    // world (dense, varied, evolved), warmed up, then grab the lowest-id (founder-lineage) cell.
    var state: SimState
    val grabbed: EntityId
    if (nCells > 0) {
        val ids = ArrayList<EntityId>()
        state = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            for (i in 0 until nCells) {
                val bio = if (i == 0) 200 else 2
                ids += b.spawnCell(
                    CytoUnits.coord2(i * 1.5f, 0f), Coord2.zero, CellType.Blank,
                    biomass = mapOf("ab" to bio), logicalRadius = org.emerge.demo.cyto.sim.MIN_RADIUS,
                )
            }
            for (i in 0 until nCells - 1) addSpring(b, ids[i], ids[i + 1], cfg)
            b.build()
        }
        grabbed = ids[0]
    } else {
        state = org.emerge.demo.cyto.sim.createCytoInitialState()
        repeat(600) { state = reducer.reduce(cfg, state, mapOf(PlayerId(0) to CytoInput.EMPTY)) }
        grabbed = state.components.getTable<org.emerge.demo.cyto.sim.CytoCellComponent>().asMap().keys.minByOrNull { it.value }!!
    }
    val centerX = 0f; val centerY = 0f

    fun stats(): DoubleArray {
        val transforms = state.components.getTable<TransformComponent>()
        val motions = state.components.getTable<MotionComponent>()
        val materials = state.components.getTable<MaterialComponent>()
        var maxV = 0.0; var ke = 0.0
        for (id in state.components.getTable<org.emerge.demo.cyto.sim.CytoCellComponent>().asMap().keys) {
            val v = motions[id]?.vel ?: continue
            val vx = CytoUnits.toLogical(v.x).toDouble(); val vy = CytoUnits.toLogical(v.y).toDouble()
            val sp = sqrt(vx * vx + vy * vy); if (sp > maxV) maxV = sp
            val mass = (materials[id]?.mass ?: 1u).toLong().toDouble()
            ke += 0.5 * mass * (vx * vx + vy * vy)
        }
        var maxStretch = 0.0
        val springs = state.components.getTable<SpringConstraintComponent>().asMap()
        for ((id, comp) in springs) {
            val pa = transforms[id]?.pos ?: continue
            for (s in comp.springs) {
                val pb = transforms[s.other]?.pos ?: continue
                val d = CytoUnits.toLogical((pb - pa).len).toDouble()
                val stretch = kotlin.math.abs(d - CytoUnits.toLogical(s.restLength).toDouble())
                if (stretch > maxStretch) maxStretch = stretch
            }
        }
        val gp = transforms[grabbed]?.pos
        return doubleArrayOf(maxV, ke, maxStretch, gp?.let { CytoUnits.toLogical(it.x).toDouble() } ?: 0.0,
            gp?.let { CytoUnits.toLogical(it.y).toDouble() } ?: 0.0)
    }

    fun fmt(d: Double) = ((d * 1000).toLong() / 1000.0).toString()
    println("cells=$nCells radius=$radius period=$period grabStiffness=${cfg.grabStiffness.toFloat()} grabDamping=${cfg.grabDamping.toFloat()}")
    println("tick\ttargetX\ttargetY\tmaxSpeed\tkinetic\tmaxStretch\tcomLag")
    for (t in 0..ticks) {
        val ang = 2.0 * PI * t / period
        val tx = (centerX + radius * cos(ang)).toFloat()
        val ty = (centerY + radius * sin(ang)).toFloat()
        if (t > 0) state = reducer.reduce(cfg, state, mapOf(PlayerId(0) to CytoInput(grab = CytoInput.Grab(grabbed, tx, ty, sticky = false))))
        if (t % every == 0) {
            val s = stats()
            val lag = sqrt((s[3] - tx) * (s[3] - tx) + (s[4] - ty) * (s[4] - ty))
            println("$t\t${fmt(tx.toDouble())}\t${fmt(ty.toDouble())}\t${fmt(s[0])}\t${fmt(s[1])}\t${fmt(s[2])}\t${fmt(lag)}")
        }
    }
}
