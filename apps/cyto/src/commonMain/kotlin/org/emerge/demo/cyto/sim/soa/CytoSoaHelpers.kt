package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.math.max
import kotlin.math.min

// ──────────────────────────────────────────────────────────────────────────────
// Helper functions (extracted from CytoSoaReducer companion/object scope)
// ──────────────────────────────────────────────────────────────────────────────

/** The number of Jacobi iterations used by the spring constraint solver. */
const val ITERATIONS = 3

/** Frac fixed-point scale (= Int.MAX_VALUE as Long). */
const val FRAC_MAX = 2147483647L  // Int.MAX_VALUE

/** Exact replica of Frac2.len(x,y) on raw longs (no Frac2 allocation). */
private val SQRT_MAX_INT: Long = longISqrt(FRAC_MAX)

fun transformAt(w: CytoWorld, slot: Int): TransformComponent =
    TransformComponent(Coord2(Coord(w.posX[slot]), Coord(w.posY[slot])), Coord(w.ang[slot]))

fun delta(w: CytoWorld, a: Int, b: Int): Frac2 =
    Coord2(Coord(w.posX[b]), Coord(w.posY[b])) - Coord2(Coord(w.posX[a]), Coord(w.posY[a]))

fun deltaLen(w: CytoWorld, a: Int, b: Int): Frac = delta(w, a, b).len

/** Whether slot [i] has a CSR edge to entity-id [otherId]. */
fun edgeExists(w: CytoWorld, i: Int, otherId: Int): Boolean {
    for (k in w.csr.offset[i] until w.csr.offset[i + 1]) if (w.csr.otherId[k] == otherId) return true
    return false
}

/** Does the weld [i]–[nSlot] pass ~through a common welded neighbour B? */
fun throughCellChord(w: CytoWorld, i: Int, nSlot: Int, cfg: CytoConfig): Boolean {
    val cosSq = cfg.weldCollinearCos * cfg.weldCollinearCos
    for (k2 in w.csr.offset[i] until w.csr.offset[i + 1]) {
        val b = w.csr.otherSlot[k2]
        if (b < 0 || b == nSlot) continue
        var common = false
        for (k3 in w.csr.offset[nSlot] until w.csr.offset[nSlot + 1]) {
            if (w.csr.otherSlot[k3] == b) { common = true; break }
        }
        if (!common) continue
        val bix = (w.posX[i] - w.posX[b]).toFloat(); val biy = (w.posY[i] - w.posY[b]).toFloat()
        val bjx = (w.posX[nSlot] - w.posX[b]).toFloat(); val bjy = (w.posY[nSlot] - w.posY[b]).toFloat()
        val dot = bix * bjx + biy * bjy
        if (dot >= 0f) continue
        val la2 = bix * bix + biy * biy; val lb2 = bjx * bjx + bjy * bjy
        if (dot * dot > cosSq * la2 * lb2) return true
    }
    return false
}

/** Rebuild the CSR dropping the [broken] pairs (both directions). */
fun pruneEdges(w: CytoWorld, broken: HashSet<Long>) {
    val keep = HashMap<Int, MutableList<SpringConstraint>>(w.count)
    val dmg = HashMap<Int, HashMap<EntityId, Float>>(w.count)
    for (slot in 0 until w.count) {
        val ownerId = w.entityId[slot]
        for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
            val otherId = w.csr.otherId[k]
            if (broken.contains(pairKey(ownerId, otherId))) continue
            keep.getOrPut(ownerId) { ArrayList() }
                .add(SpringConstraint(EntityId(otherId), Frac(w.csr.restRaw[k]), Frac(w.csr.stiffRaw[k]), Frac(w.csr.dampRaw[k])))
            dmg.getOrPut(ownerId) { HashMap() }[EntityId(otherId)] = w.csr.edgeAux[k]
        }
    }
    w.csr.rebuildFrom(
        count = w.count,
        entityIdAt = { w.entityId[it] },
        slotOf = { w.slotOf(it) },
        springsAt = { slot -> keep[w.entityId[slot]] ?: emptyList() },
        edgeAuxAt = { slot, other -> dmg[w.entityId[slot]]?.get(other) ?: 0f },
    )
}

/** Canonical undirected pair key (low/high entity-id packed into a Long). */
fun pairKey(a: Int, b: Int): Long {
    val lo = min(a, b); val hi = max(a, b)
    return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
}

/** Insertion sort (used by contact broadphase neighbour sorting). */
fun insertionSort(a: IntArray, size: Int) {
    for (i in 1 until size) {
        val v = a[i]; var j = i - 1
        while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j -= 1 }
        a[j + 1] = v
    }
}

/** Absolute value for Long. */
fun longAbs(v: Long): Long = if (v < 0L) -v else v

/** Integer sqrt, identical to Frac2.longISqrt. */
fun longISqrt(n: Long, min: Long = 2L, max: Long = 2L * FRAC_MAX): Long {
    if (n < 2) return n
    var x = kotlin.math.sqrt(n.toDouble()).toLong()
    if (x < 1L) x = 1L
    while (x > n / x) x--
    while (x + 1L <= n / (x + 1L)) x++
    return if (x < min) min else if (x > max) max else x
}

/** Raw-space integer hypot, identical to Frac2.len. */
fun lenRaw(xr: Long, yr: Long): Long {
    val ax = if (xr < 0L) -xr else xr
    val ay = if (yr < 0L) -yr else yr
    if (ax == 0L) return ay
    if (ay == 0L) return ax
    return if (ax <= FRAC_MAX && ay <= FRAC_MAX) longISqrt(ax * ax + ay * ay)
    else longISqrt(ax * ax / FRAC_MAX + ay * ay / FRAC_MAX, 2L, ax + ay) * SQRT_MAX_INT
}

/** Handle contact: weld decision + repulsion impulse. */
fun handleContact(w: CytoWorld, i: Int, j: Int, contact: Contact, cfg: CytoConfig, state: CytoPipelineState) {
    val sticky = w.cell.sticky[i] || w.cell.stickyTemp[i] || w.cell.sticky[j] || w.cell.stickyTemp[j]
    val close = CytoTuning.AUTO_WELD_ON_OVERLAP && contact.penetration.raw * 4L > contact.minDist.raw
    if (sticky || close) {
        val ai = w.entityId[i]; val bi = w.entityId[j]
        if (ai < bi) { state.weldLo.add(ai); state.weldHi.add(bi) } else { state.weldLo.add(bi); state.weldHi.add(ai) }
        return
    }
    state.touchScratch[i]++; state.touchScratch[j]++
    state.touchingScratch[i].add(w.entityId[j]); state.touchingScratch[j].add(w.entityId[i])
    val massA = w.mass[i].toUInt(); val massB = w.mass[j].toUInt()
    val total = (massA + massB).toLong()
    if (total <= 0L) return
    val weightA = Frac(massB.toLong(), total.toInt())
    val weightB = Frac(massA.toLong(), total.toInt())
    val normal = contact.normal
    val vn = Frac2(
        Frac((w.velX[i].toLong() + w.impVelX[i]) - (w.velX[j].toLong() + w.impVelX[j])),
        Frac((w.velY[i].toLong() + w.impVelY[i]) - (w.velY[j].toLong() + w.impVelY[j])),
    ).dot(normal)
    val effective = contact.penetration * cfg.repulsion - vn * cfg.contactDamping
    val impA = normal * (effective * weightA)
    val impB = -(normal * (effective * weightB))
    w.impVelX[i] += impA.x.raw; w.impVelY[i] += impA.y.raw
    w.impVelX[j] += impB.x.raw; w.impVelY[j] += impB.y.raw
}
