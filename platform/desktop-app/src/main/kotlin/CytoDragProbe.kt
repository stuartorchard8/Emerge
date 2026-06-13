package org.emerge.desktop

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.sqrt

/**
 * Headless **exposed-surface drag diagnostic** (CytoDragSystem). Builds a welded line of `nCells`
 * along the x-axis, gives every cell the same initial velocity (`vx`, `vy` logical units/tick), then
 * runs with NO input and logs how the organism's speed decays:
 *  - **comSpeed** — centre-of-mass speed (should monotonically decay toward 0, never reverse),
 *  - **maxCell / minCell** — fastest/slowest cell speed.
 *
 * Run it three ways to see the asymmetry:
 *  - `1 …` (lone cell) — full isotropic drag, decays fastest per-cell.
 *  - `nCells …  vx>0 vy=0` (push ALONG the chain) — interior cells are shielded along-axis; only the
 *    leading end-cap drags, so a long chain glides far.
 *  - `nCells …  vx=0 vy>0` (push ACROSS the chain) — every cell's side is exposed, so it decays fast.
 *
 * `--args="<ticks> <printEvery> <nCells> <vx> <vy>"` (defaults 600, 50, 4, 2.0, 0.0).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 600
    val every = args.getOrNull(1)?.toIntOrNull() ?: 50
    val nCells = args.getOrNull(2)?.toIntOrNull() ?: 4
    val vx = args.getOrNull(3)?.toFloatOrNull() ?: 2.0f
    val vy = args.getOrNull(4)?.toFloatOrNull() ?: 0.0f
    val varMass = args.getOrNull(5)?.toBooleanStrictOrNull() ?: true

    val cfg = CytoConfig(variableMass = varMass)
    val reducer = CytoReducer()
    val noInput = mapOf(PlayerId(0) to CytoInput.EMPTY)

    val ids = ArrayList<EntityId>()
    var state = if (nCells == 0) {
        // Biology-active mode: the real autotroph founder (light + seeded matter grid), given a push,
        // so mass-changing biology + the variable-mass rocket run — does it settle, or drift forever?
        val s = org.emerge.demo.cyto.sim.createCytoInitialState()
        val b = SimBuilder(s)
        val founder = s.components.getTable<CytoCellComponent>().asMap().keys.first()
        b.update<MotionComponent>(founder) { (it ?: error("founder has no motion")).copy(vel = CytoUnits.coord2(vx, vy)) }
        b.build()
    } else run {
        val b = SimBuilder(SimState(randomSeed = 1))
        for (i in 0 until nCells) {
            ids += b.spawnCell(
                CytoUnits.coord2(i * 1.5f, 0f), CytoUnits.coord2(vx, vy), CellType.Blank,
                biomass = mapOf("ab" to 16), logicalRadius = MIN_RADIUS,
            )
        }
        for (i in 0 until nCells - 1) addSpring(b, ids[i], ids[i + 1], cfg)
        b.build()
    }

    fun speeds(): Triple<Double, Double, Double> {
        val motions = state.components.getTable<MotionComponent>()
        var sumX = 0.0; var sumY = 0.0; var maxS = 0.0; var minS = Double.MAX_VALUE; var n = 0
        for (id in state.components.getTable<CytoCellComponent>().asMap().keys) {
            val v = motions[id]?.vel ?: continue
            val sx = CytoUnits.toLogical(v.x).toDouble(); val sy = CytoUnits.toLogical(v.y).toDouble()
            val sp = sqrt(sx * sx + sy * sy)
            sumX += sx; sumY += sy; n++
            if (sp > maxS) maxS = sp
            if (sp < minS) minS = sp
        }
        if (n == 0) return Triple(0.0, 0.0, 0.0)
        return Triple(sqrt((sumX / n) * (sumX / n) + (sumY / n) * (sumY / n)), maxS, minS)
    }

    fun fmt(d: Double) = ((d * 10000).toLong() / 10000.0).toString()
    println("nCells=$nCells push=($vx,$vy) dragCoefficient=${cfg.dragCoefficient}")
    println("tick\tcomSpeed\tmaxCell\tminCell")
    for (t in 0..ticks) {
        if (t > 0) state = reducer.reduce(cfg, state, noInput)
        if (t % every == 0) {
            val (com, mx, mn) = speeds()
            println("$t\t${fmt(com)}\t${fmt(mx)}\t${fmt(mn)}")
        }
    }
}
