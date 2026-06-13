package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import java.io.File
import kotlin.math.sqrt

/**
 * Loads a Cyto save and dissects its **self-propelling colonies**: groups cells into colonies (spring-
 * connected components) and, for the fastest-moving ones, reports whether the motion is **physics**
 * (cells carry real velocity / momentum) or **growth-creep** (cells ~stationary, but the colony's
 * centroid migrates as cells are born toward resources and die behind) — and prints the genomes so we
 * can see the mechanism. Then runs the world forward, tracking the population centroid + mean cell
 * speed + net momentum, to see drift accumulate. `--args="<savePath> <ticks>"`.
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "platform/desktop-app/cyto-save.bin"
    val ticks = args.getOrNull(1)?.toIntOrNull() ?: 1000
    val bytes = File(path).readBytes()
    var state = CytoSaveCodec.decode(bytes)
    println("loaded $path: ${cells(state).size} cells")

    // ── colonies (spring-connected components) ──
    val colonies = components(state)
    println("colonies: ${colonies.size}")
    val ranked = colonies.map { it to comSpeed(state, it) }.sortedByDescending { it.second }
    println("\ntop colonies by COM speed (logical/tick):")
    println("size\tcomSpeed\tmeanCellSpeed\t|momentum|\tverdict")
    for ((colony, cs) in ranked.take(6)) {
        val mcs = meanCellSpeed(state, colony)
        val mom = momentum(state, colony)
        // Physics propulsion ⇒ cells actually move (mean speed ≈ COM speed). Growth-creep ⇒ cells
        // ~stationary (mean speed ≈ 0) but the COM still drifts via births/deaths.
        val verdict = if (mcs < cs * 0.25 + 1e-4) "growth-creep?" else "physics?"
        println("${colony.size}\t${f(cs)}\t${f(mcs)}\t${f(mom)}\t$verdict")
    }

    // ── the fastest colony's genome(s) ──
    val top = ranked.firstOrNull { it.first.size > 1 }?.first
    if (top != null) {
        println("\nfastest colony (${top.size} cells) genomes:")
        val cellMap = cells(state)
        top.map { GeneCodec.serialize(cellMap.getValue(it).genome) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (g, n) -> println("  ×$n:\n${g.lines().joinToString("\n") { "      $it" }}") }
    }

    // ── run forward: does the population centroid drift while cells stay slow? ──
    // Optional ablation overrides: <dragCoefficient> <variableMass true/false> <connectionBreakDamage>.
    val base = CytoConfig()
    val cfg = base.copy(
        dragCoefficient = args.getOrNull(2)?.toFloatOrNull() ?: base.dragCoefficient,
        variableMass = args.getOrNull(3)?.toBooleanStrictOrNull() ?: base.variableMass,
        connectionBreakDamage = args.getOrNull(4)?.toFloatOrNull() ?: base.connectionBreakDamage,
    )
    println("\ncfg: drag=${cfg.dragCoefficient} variableMass=${cfg.variableMass} breakDamage=${cfg.connectionBreakDamage}")
    val reducer = CytoReducer()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)
    var prevC = centroid(state)
    var netDx = 0.0; var netDy = 0.0
    println("\nforward run (population): tick\tpop\tmeanSpeed\t|momentum|\tnetCentroidDrift")
    for (t in 1..ticks) {
        state = reducer.reduce(cfg, state, input)
        if (t % (ticks / 10).coerceAtLeast(1) == 0) {
            val c = centroid(state)
            netDx += wrapDelta(c.first - prevC.first); netDy += wrapDelta(c.second - prevC.second)
            prevC = c
            val drift = sqrt(netDx * netDx + netDy * netDy)
            println("$t\t${cells(state).size}\t${f(meanCellSpeedAll(state))}\t${f(momentumAll(state))}\t${f(drift)}")
        }
    }
}

private fun cells(s: SimState) = s.components.getTable<CytoCellComponent>().asMap()

private fun components(s: SimState): List<Set<EntityId>> {
    val adj = HashMap<EntityId, MutableSet<EntityId>>()
    for (id in cells(s).keys) adj[id] = mutableSetOf()
    for ((id, sc) in s.components.getTable<SpringConstraintComponent>().asMap()) {
        for (sp in sc.springs) { adj[id]?.add(sp.other); adj[sp.other]?.add(id) }
    }
    val seen = HashSet<EntityId>()
    val out = ArrayList<Set<EntityId>>()
    for (id in cells(s).keys) {
        if (id in seen) continue
        val comp = HashSet<EntityId>(); val stack = ArrayDeque<EntityId>(); stack.add(id)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast(); if (!seen.add(n)) continue; comp.add(n)
            adj[n]?.forEach { if (it !in seen) stack.add(it) }
        }
        out.add(comp)
    }
    return out
}

private fun vel(s: SimState, id: EntityId): Pair<Double, Double> {
    val v = s.components.getTable<MotionComponent>().asMap()[id]?.vel ?: return 0.0 to 0.0
    return CytoUnits.toLogical(v.x).toDouble() to CytoUnits.toLogical(v.y).toDouble()
}
private fun mass(s: SimState, id: EntityId) = (s.components.getTable<MaterialComponent>().asMap()[id]?.mass ?: 1u).toLong().toDouble()

private fun momentum(s: SimState, ids: Collection<EntityId>): Double {
    var px = 0.0; var py = 0.0
    for (id in ids) { val (vx, vy) = vel(s, id); val m = mass(s, id); px += m * vx; py += m * vy }
    return sqrt(px * px + py * py)
}
private fun comSpeed(s: SimState, ids: Collection<EntityId>): Double {
    var px = 0.0; var py = 0.0; var m = 0.0
    for (id in ids) { val (vx, vy) = vel(s, id); val mi = mass(s, id); px += mi * vx; py += mi * vy; m += mi }
    return if (m > 0) sqrt(px * px + py * py) / m else 0.0
}
private fun meanCellSpeed(s: SimState, ids: Collection<EntityId>): Double {
    if (ids.isEmpty()) return 0.0
    var sum = 0.0; for (id in ids) { val (vx, vy) = vel(s, id); sum += sqrt(vx * vx + vy * vy) }
    return sum / ids.size
}
private fun meanCellSpeedAll(s: SimState) = meanCellSpeed(s, cells(s).keys)
private fun momentumAll(s: SimState) = momentum(s, cells(s).keys)

private fun centroid(s: SimState): Pair<Double, Double> {
    val ts = s.components.getTable<TransformComponent>().asMap()
    var x = 0.0; var y = 0.0; var n = 0
    for (id in cells(s).keys) { val t = ts[id] ?: continue; x += CytoUnits.toLogical(t.pos.x).toDouble(); y += CytoUnits.toLogical(t.pos.y).toDouble(); n++ }
    return if (n > 0) x / n to y / n else 0.0 to 0.0
}
private fun wrapDelta(d: Double): Double {
    val span = 2.0 * CytoUnits.CELLS_PER_AXIS
    var x = d; while (x > span / 2) x -= span; while (x < -span / 2) x += span; return x
}
private fun f(d: Double) = ((d * 100000).toLong() / 100000.0).toString()
