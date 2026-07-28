package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test

/**
 * Throwaway: dump the local WELD GEOMETRY around one cell from a cyto-save (post-load id, as the app shows),
 * to diagnose a persistent through-cell degeneracy. For the target and each welded neighbour it reports the
 * weld's over-stretch ratio (dist-rest)/(2·rest); then it finds chords — a weld whose two endpoints share a
 * common welded neighbour sitting ~collinear between them (the "weld through a cell") — reporting the angle
 * at the middle cell and the chord's ratio. Run: -Dinspectcell=11 (default 11), -Dsavefile=<path>.
 */
class WeldInspect {
    @Test
    fun inspect() {
        val target = System.getProperty("inspectcell")?.toIntOrNull() ?: 11
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/apps/cyto/desktop/cyto-save.bin"
        val weldTicks = System.getProperty("weldticks")?.toIntOrNull() ?: 0   // run the reducer this many ticks first
        // This is a dump tool, not a check — it asserts nothing, and the save it reads is a local,
        // untracked artifact. Without this guard the gate passes or fails on whether the machine running
        // it happens to have that file, which is how it came to fail on the host and not the laptop.
        val file = java.io.File(path)
        if (!file.isFile) {
            println("[weld-inspect] no save at $path — nothing to inspect (pass -Dsavefile=<path> to run it)")
            return
        }
        val loaded = CytoSaveCodec.decode(file.readBytes())
        val state = if (weldTicks <= 0) loaded else run {
            val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))   // mutation off — observe the fix only
            var w = CytoWorld.fromSimState(loaded)
            repeat(weldTicks) { w = soa.tick(w, CytoInput.EMPTY) }
            w.toSimState()
        }
        val tr = state.components.getTable<TransformComponent>().asMap()
        val col = state.components.getTable<ColliderComponent>().asMap()
        val spr = state.components.getTable<SpringConstraintComponent>().asMap()
        val cells = state.components.getTable<CytoCellComponent>().asMap()
        fun px(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.x).toDouble()
        fun py(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.y).toDouble()
        fun rad(id: EntityId) = CytoUnits.toLogical(col.getValue(id).radius).toDouble()
        fun nbrs(id: EntityId) = spr[id]?.springs?.map { it.other } ?: emptyList()
        fun welded(a: EntityId, b: EntityId) = spr[a]?.springs?.any { it.other == b } == true
        fun ratio(a: EntityId, b: EntityId): Double { val d = kotlin.math.hypot(px(a) - px(b), py(a) - py(b)); val rest = rad(a) + rad(b); return (d - rest) / (2.0 * rest) }
        fun angle(at: EntityId, p: EntityId, q: EntityId): Double {
            val ax = px(p) - px(at); val ay = py(p) - py(at); val bx = px(q) - px(at); val by = py(q) - py(at)
            val c = (ax * bx + ay * by) / (kotlin.math.hypot(ax, ay) * kotlin.math.hypot(bx, by))
            return Math.toDegrees(Math.acos(c.coerceIn(-1.0, 1.0)))
        }

        val sb = StringBuilder()
        sb.appendLine("save=$path tick=${state.tick} cells=${cells.size}")
        val id = EntityId(target)
        if (!cells.containsKey(id)) { sb.appendLine("NO cell $target"); java.io.File("/tmp/cytoweld.txt").writeText(sb.toString()); println(sb); return }
        val ns = nbrs(id)
        sb.appendLine("\n=== cell $target  pos=(${"%.2f".format(px(id))},${"%.2f".format(py(id))}) radius=${"%.3f".format(rad(id))} degree=${ns.size} ===")
        sb.appendLine("welds from $target:")
        for (nb in ns.sortedBy { it.value }) sb.appendLine("  $target–${nb.value}: dist=${"%.3f".format(kotlin.math.hypot(px(id)-px(nb), py(id)-py(nb)))} rest=${"%.3f".format(rad(id)+rad(nb))} ratio=${"%.2f".format(ratio(id, nb))}")

        sb.appendLine("\nchords THROUGH $target (it is the middle cell B of a welded pair):")
        var found = 0
        for (i in ns.indices) for (j in i + 1 until ns.size) {
            val p = ns[i]; val q = ns[j]
            if (!welded(p, q)) continue
            val ang = angle(id, p, q)
            sb.appendLine("  ${p.value}–${q.value} welded, angle at $target = ${"%.0f".format(ang)}°, chord ratio=${"%.2f".format(ratio(p, q))}  ${if (ang > 134) "<-- DEGENERATE (through $target)" else ""}")
            if (ang > 134) found++
        }

        sb.appendLine("\nchords FROM $target (it is an endpoint A/C, with a middle cell between):")
        for (x in ns) {
            val common = nbrs(id).toSet().intersect(nbrs(x).toSet())
            for (b in common) {
                val ang = angle(b, id, x)
                if (ang > 134) { sb.appendLine("  $target–${x.value} welded, with ${b.value} between (angle at ${b.value} = ${"%.0f".format(ang)}°), chord ratio=${"%.2f".format(ratio(id, x))}  <-- DEGENERATE"); found++ }
            }
        }
        sb.appendLine("\ndegenerate chords found around $target: $found")
        java.io.File("/tmp/cytoweld.txt").writeText(sb.toString())
        println(sb)
    }
}
