package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.sim.core.EntityId
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
    // "fresh" → grow a colony from the default world instead of loading a save (to probe an
    // actively-dividing population, where division placement matters).
    var state = if (path == "fresh") org.emerge.demo.cyto.sim.createCytoInitialState()
        else CytoSaveCodec.decode(File(path).readBytes())
    println("loaded $path: ${cells(state).size} cells")

    // ── matter budget: where are the atoms, and is matter the population cap? (A save carries its OWN
    //    serialized reservoir, so MATTER_PEAK changes only affect FRESH worlds, not a loaded save.) ──
    run {
        val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
        val reservoir = grid?.totalAtoms() ?: 0L
        var cellAtoms = 0L
        for ((_, c) in cells(state)) {
            for ((sp, n) in c.cytoplasm) cellAtoms += sp.length.toLong() * n
            for ((sp, n) in c.biomass) cellAtoms += sp.length.toLong() * n
        }
        println("matter: reservoir=$reservoir  cells=$cellAtoms  total=${reservoir + cellAtoms}")
        // per-source: bucket cells + reservoir matter by nearest light source
        val srcCells = IntArray(CytoLightField.SOURCES.size)
        for ((id, _) in cells(state)) {
            val p = state.components.getTable<TransformComponent>()[id]?.pos ?: continue
            val lx = CytoUnits.toLogical(p.x); val ly = CytoUnits.toLogical(p.y)
            var best = 0; var bestD = Float.MAX_VALUE
            for ((i, s) in CytoLightField.SOURCES.withIndex()) {
                val d = (lx - s.first) * (lx - s.first) + (ly - s.second) * (ly - s.second)
                if (d < bestD) { bestD = d; best = i }
            }
            srcCells[best]++
        }
        println("cells per source (nearest): ${srcCells.toList()}")
        val cs = cells(state).values
        val avgBio = if (cs.isEmpty()) 0.0 else cs.sumOf { c -> c.biomass.entries.sumOf { (sp, n) -> (sp.length - 1).coerceAtLeast(0) * n } }.toDouble() / cs.size
        val avgCyto = if (cs.isEmpty()) 0.0 else cs.sumOf { c -> c.cytoplasm.entries.sumOf { (sp, n) -> sp.length * n } }.toDouble() / cs.size
        println("avg biomass-bonds/cell=${(avgBio*10).toInt()/10.0}  avg cytoplasm-atoms/cell=${(avgCyto*10).toInt()/10.0}")
    }

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
        repulsion = args.getOrNull(5)?.toIntOrNull()?.let { org.emerge.sim.core.physics.primitives.Frac(it.toLong(), 100) } ?: base.repulsion,
    )
    println("\ncfg: drag=${cfg.dragCoefficient} variableMass=${cfg.variableMass} breakDamage=${cfg.connectionBreakDamage} repulsion=${cfg.repulsion.toFloat()}")
    val sim = CytoSoaSim(cfg, state)

    // One-shot: dissect the fastest unconnected cell — is it isolated in space (→ drag isn't acting,
    // a bug) or touching another cell (→ a contact force balances drag)?
    run {
        val sc = state.components.getTable<SpringConstraintComponent>().asMap()
        val ts = state.components.getTable<TransformComponent>().asMap()
        val lone = cells(state).keys.filter { (sc[it]?.springs?.size ?: 0) == 0 }
        val fastest = lone.maxByOrNull { val (vx, vy) = vel(state, it); vx * vx + vy * vy }
        if (fastest != null) {
            val (vx, vy) = vel(state, fastest)
            val rawV = state.components.getTable<MotionComponent>().asMap()[fastest]?.vel
            val p = ts[fastest]?.pos
            val r = CytoUnits.toLogical(state.components.getTable<org.emerge.sim.core.physics.components.ColliderComponent>().asMap()[fastest]?.radius ?: org.emerge.sim.core.physics.primitives.Frac(0)).toDouble()
            var nearest = Double.MAX_VALUE
            for (other in cells(state).keys) {
                if (other == fastest) continue
                val po = ts[other] ?: continue
                val d = CytoUnits.toLogical((po.pos - (p ?: po.pos)).len).toDouble()
                if (d < nearest) nearest = d
            }
            println("\nfastest lone cell $fastest: speed=${f(sqrt(vx*vx+vy*vy))} velRaw=(${rawV?.x?.raw},${rawV?.y?.raw}) radius=${f(r)} nearestCellDist=${f(nearest)} (touching if < ~${f(2*r)})")
        }
    }

    var prevC = centroid(state)
    var netDx = 0.0; var netDy = 0.0
    fun springs(s: SimState) = s.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size } / 2
    // Max speed among UNCONNECTED cells (no springs) — does an isolated single cell drift or settle?
    fun maxLoneSpeed(s: SimState): Double {
        val sc = s.components.getTable<SpringConstraintComponent>().asMap()
        var mx = 0.0
        for (id in cells(s).keys) {
            if ((sc[id]?.springs?.size ?: 0) > 0) continue
            val (vx, vy) = vel(s, id); val sp = sqrt(vx * vx + vy * vy)
            if (sp > mx) mx = sp
        }
        return mx
    }
    println("\nforward run (population): tick\tpop\tsprings\tmeanSpeed\tmaxLoneSpeed\t|momentum|\tmaxStretch")
    for (t in 1..ticks) {
        state = sim.step()
        if (t % (ticks / 10).coerceAtLeast(1) == 0) {
            val c = centroid(state)
            netDx += wrapDelta(c.first - prevC.first); netDy += wrapDelta(c.second - prevC.second)
            prevC = c
            println("$t\t${cells(state).size}\t${springs(state)}\t${f(meanCellSpeedAll(state))}\t${f(maxLoneSpeed(state))}\t${f(momentumAll(state))}\t${f(maxStretch(state))}")
        }
    }
    println("\n(breaking needs stretch > 0.5 logical/tick sustained — stress = stretch·0.5 − 0.25, break at damage > ${cfg.connectionBreakDamage})")
}

/** Worst spring |dist − rest| over the world, in logical units — the breaking trigger's input. */
private fun maxStretch(s: SimState): Double {
    val ts = s.components.getTable<TransformComponent>().asMap()
    var mx = 0.0
    for ((id, sc) in s.components.getTable<SpringConstraintComponent>().asMap()) {
        val pa = ts[id]?.pos ?: continue
        for (sp in sc.springs) {
            val pb = ts[sp.other]?.pos ?: continue
            val stretch = kotlin.math.abs(CytoUnits.toLogical((pb - pa).len).toDouble() - CytoUnits.toLogical(sp.restLength).toDouble())
            if (stretch > mx) mx = stretch
        }
    }
    return mx
}

/** Worst accumulated connection-stress damage over the world (breaks past connectionBreakDamage). */
private fun maxDamage(s: SimState): Double {
    var mx = 0.0
    for ((_, cs) in s.components.getTable<org.emerge.demo.cyto.sim.ConnectionStateComponent>().asMap()) {
        for (d in cs.damage.values) if (d > mx) mx = d.toDouble()
    }
    return mx
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
