package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimState
import kotlin.math.sqrt

/**
 * Headless **locomotion diagnostic**: is the colony's emergent movement a genuine (momentum-conserving)
 * gait, or an artifact that *creates* momentum/energy from nothing? Seeds the default world and prints,
 * per checkpoint, the conserved-quantity traces:
 *  - **|momentum|** = |Σ mass·v| — should stay ~0 if internal forces are the only thing moving cells;
 *    a steady climb from 0 means momentum is being minted (prime suspect: division copying the mother's
 *    velocity to the daughter — see CytoLifecycleSystem.divide).
 *  - **comSpeed** = |momentum| / Σmass — the actual net drift rate of the population.
 *  - **kinetic** = Σ ½·mass·v² — a climb with no input means the (explicit) spring solver is injecting energy.
 *  - **maxSpeed** — fastest single cell (sanity on scale).
 *
 * `--args="<ticks> <printEvery> <mutationRateDenom> <repulsion 0|1>"` (defaults 8000, 500, config
 * default, 1). Pass repulsion 0 to ablate contact repulsion (does the drift survive without it?).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val every = args.getOrNull(1)?.toIntOrNull() ?: 500
    var cfg = CytoConfig()
    args.getOrNull(2)?.toIntOrNull()?.let { cfg = cfg.copy(mutationRateDenom = it) }
    val repulsionOn = (args.getOrNull(3)?.toIntOrNull() ?: 1) != 0
    if (!repulsionOn) cfg = cfg.copy(repulsion = Frac(0))
    val rocketOn = (args.getOrNull(4)?.toIntOrNull() ?: 1) != 0
    if (!rocketOn) cfg = cfg.copy(variableMass = false)

    var state = createCytoInitialState()
    val sim = CytoSoaSim(cfg, state)

    fun pop() = state.components.getTable<CytoCellComponent>().asMap().size

    /** [px, py, totalMass, kinetic, maxSpeed] in logical units (mass in engine UInt units). */
    fun kinematics(): DoubleArray {
        val cells = state.components.getTable<CytoCellComponent>().asMap()
        val motions = state.components.getTable<MotionComponent>()
        val materials = state.components.getTable<MaterialComponent>()
        var px = 0.0; var py = 0.0; var mass = 0.0; var ke = 0.0; var maxV = 0.0
        for (id in cells.keys) {
            val v = motions[id]?.vel ?: continue
            val m = (materials[id]?.mass ?: 1u).toLong().toDouble()
            val vx = CytoUnits.toLogical(v.x).toDouble()
            val vy = CytoUnits.toLogical(v.y).toDouble()
            px += m * vx; py += m * vy; mass += m
            ke += 0.5 * m * (vx * vx + vy * vy)
            val sp = sqrt(vx * vx + vy * vy)
            if (sp > maxV) maxV = sp
        }
        return doubleArrayOf(px, py, mass, ke, maxV)
    }

    fun fmt(d: Double) = ((d * 1_000_000).toLong() / 1_000_000.0).toString()
    println("repulsion=${if (repulsionOn) "on" else "OFF"} rocket(variableMass)=${if (rocketOn) "on" else "OFF"} mutationRateDenom=${cfg.mutationRateDenom}")
    println("tick\tpop\t|momentum|\tcomSpeed\tkinetic\tmaxSpeed")
    fun line(t: Int) {
        val k = kinematics()
        val mom = sqrt(k[0] * k[0] + k[1] * k[1])
        val comSpeed = if (k[2] > 0) mom / k[2] else 0.0
        println("$t\t${pop()}\t${fmt(mom)}\t${fmt(comSpeed)}\t${fmt(k[3])}\t${fmt(k[4])}")
    }
    line(0)
    for (t in 1..ticks) {
        state = sim.step()
        if (t % every == 0) line(t)
    }
}
