package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Throwaway locomotion diagnostic. Loads a cyto-save (the hand-assembled organism), runs the SoA reducer
 * (mutation OFF), isolates ONE cell's welded cluster (the "organism"), and asks the question that decides
 * whether swimming is even *possible*:
 *
 *   - does the body drift at all? (net centre-of-mass displacement)
 *   - is it actually contracting? (temporal amplitude of the cluster's mean radius)
 *   - is the contraction a SYNCHRONISED BREATH (all cells in phase -> reciprocal -> scallop theorem ->
 *     zero net thrust no matter how hard it pulses) or a PHASED TRAVELLING WAVE (cells out of phase across
 *     the body -> non-reciprocal -> can produce thrust)? Measured two ways:
 *       (a) spatial std of radius at each instant vs the temporal amplitude of the mean radius —
 *           breath = big temporal swing, ~0 spatial spread; wave = large spatial spread.
 *       (b) correlation of the two halves' mean radius over time — +1 in-phase (breath), -1 anti-phase (wave).
 *   - is there any COM momentum (do the internal forces ever net into a push on the whole body)?
 *
 * Gated:
 *   ./gradlew :apps:cyto:jvmTest --tests "*SwimProbe*" -Dswimprobe=1 \
 *      [-Dswimcell=100] [-Dswimticks=20000] [-Dsavefile=...]
 *   -> /tmp/swimprobe.txt
 */
class SwimProbe {
    @Test
    fun run() {
        if (System.getProperty("swimprobe") == null) return
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/platform/desktop-app/cyto-save.bin"
        val ticks = System.getProperty("swimticks")?.toIntOrNull() ?: 20000
        val seedId = System.getProperty("swimcell")?.toIntOrNull() ?: 100

        val state = CytoSaveCodec.decode(java.io.File(path).readBytes())
        val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))
        var w = CytoWorld.fromSimState(state)

        val sb = StringBuilder()
        sb.appendLine("=== swim probe ($path, $ticks ticks, mutation OFF) ===")
        sb.appendLine("loaded tick=${state.tick}  cells=${w.count}  post-load id range ${w.entityId.take(w.count).minOrNull()}..${w.entityId.take(w.count).maxOrNull()}")

        // ── pick the organism: the welded cluster containing `seedId`; fall back to the largest cluster ──
        fun clusterSlots(startSlot: Int): IntArray {
            val seen = HashSet<Int>(); val stack = ArrayDeque<Int>()
            stack.addLast(startSlot); seen.add(startSlot)
            while (stack.isNotEmpty()) {
                val s = stack.removeLast()
                for (k in w.csr.offset[s] until w.csr.offset[s + 1]) {
                    val nb = w.csr.otherSlot[k]
                    if (nb >= 0 && seen.add(nb)) stack.addLast(nb)
                }
            }
            return seen.toIntArray()
        }
        val seedSlot = w.slotOf(seedId)
        val (clusterIds, pickedNote) = if (seedSlot >= 0) {
            clusterSlots(seedSlot).map { w.entityId[it] }.toIntArray() to "cluster of cell $seedId"
        } else {
            // largest cluster
            val visited = BooleanArray(w.count); var best = IntArray(0)
            for (s in 0 until w.count) {
                if (visited[s]) continue
                val c = clusterSlots(s); for (x in c) visited[x] = true
                if (c.size > best.size) best = c
            }
            best.map { w.entityId[it] }.toIntArray() to "cell $seedId not found; using LARGEST cluster"
        }
        val idSet = clusterIds.toHashSet()
        sb.appendLine("organism: $pickedNote  -> ${clusterIds.size} cells")
        sb.appendLine()

        // ── per-tick measurement of the organism (resolve ids -> current slots each tick) ──
        val n = ticks + 1
        val comX = DoubleArray(n); val comY = DoubleArray(n)
        val meanR = DoubleArray(n); val stdR = DoubleArray(n)
        val halfA = DoubleArray(n); val halfB = DoubleArray(n)   // mean radius of the two halves along the long axis
        val comVel = DoubleArray(n)                              // mass-weighted COM speed (logical cell-diam / tick)
        val maxSpeed = DoubleArray(n)                            // max per-cell speed from the VELOCITY channel (velX/velY)
        val posMotion = DoubleArray(n)                           // mean per-cell |Δpos| this tick RELATIVE TO COM (real shape change)
        var alive = clusterIds.size
        val prevRelX = HashMap<Int, Double>(); val prevRelY = HashMap<Int, Double>()  // id -> last pos relative to COM

        // long axis (fixed from tick 0 geometry) so the half-split is stable
        var axisX = 1.0; var axisY = 0.0

        fun measure(t: Int): Boolean {
            var mTot = 0.0; var cx = 0.0; var cy = 0.0
            var px = 0.0; var py = 0.0
            var rSum = 0.0; var rSq = 0.0; var cnt = 0; var mx = 0.0
            // gather slots
            val slots = ArrayList<Int>(idSet.size)
            for (id in idSet) { val s = w.slotOf(id); if (s in 0 until w.count) slots.add(s) }
            if (slots.isEmpty()) return false
            for (s in slots) {
                val m = w.mass[s].toDouble()
                val x = CytoUnits.toLogical(Coord(w.posX[s])).toDouble()
                val y = CytoUnits.toLogical(Coord(w.posY[s])).toDouble()
                val vx = CytoUnits.toLogical(Coord(w.velX[s])).toDouble()
                val vy = CytoUnits.toLogical(Coord(w.velY[s])).toDouble()
                val r = Frac(w.cell.logicalRadius[s]).toFloat().toDouble()
                mTot += m; cx += m * x; cy += m * y; px += m * vx; py += m * vy
                rSum += r; rSq += r * r; cnt++
                val sp = hypot(vx, vy); if (sp > mx) mx = sp
            }
            cx /= mTot; cy /= mTot
            comX[t] = cx; comY[t] = cy
            comVel[t] = hypot(px, py) / mTot
            maxSpeed[t] = mx
            meanR[t] = rSum / cnt
            stdR[t] = sqrt((rSq / cnt - (rSum / cnt) * (rSum / cnt)).coerceAtLeast(0.0))
            // half-split along the fixed long axis (project each cell onto the axis, side = sign)
            var aSum = 0.0; var aN = 0; var bSum = 0.0; var bN = 0
            for (s in slots) {
                val x = CytoUnits.toLogical(Coord(w.posX[s])).toDouble() - cx
                val y = CytoUnits.toLogical(Coord(w.posY[s])).toDouble() - cy
                val proj = x * axisX + y * axisY
                val r = Frac(w.cell.logicalRadius[s]).toFloat().toDouble()
                if (proj >= 0) { aSum += r; aN++ } else { bSum += r; bN++ }
            }
            halfA[t] = if (aN > 0) aSum / aN else 0.0
            halfB[t] = if (bN > 0) bSum / bN else 0.0
            // real positional shape change: per-cell |Δpos relative to COM| since last tick
            var move = 0.0; var moveN = 0
            for (s in slots) {
                val id = w.entityId[s]
                val rx = CytoUnits.toLogical(Coord(w.posX[s])).toDouble() - cx
                val ry = CytoUnits.toLogical(Coord(w.posY[s])).toDouble() - cy
                val pxR = prevRelX[id]; val pyR = prevRelY[id]
                if (pxR != null && pyR != null) { move += hypot(rx - pxR, ry - pyR); moveN++ }
                prevRelX[id] = rx; prevRelY[id] = ry
            }
            posMotion[t] = if (moveN > 0) move / moveN else 0.0
            alive = slots.size
            return true
        }

        // establish the long axis from tick-0 geometry (PCA-lite: axis = max-extent direction)
        run {
            val slots = ArrayList<Int>(); for (id in idSet) { val s = w.slotOf(id); if (s in 0 until w.count) slots.add(s) }
            var minx = Double.MAX_VALUE; var maxx = -Double.MAX_VALUE; var miny = Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
            for (s in slots) {
                val x = CytoUnits.toLogical(Coord(w.posX[s])).toDouble(); val y = CytoUnits.toLogical(Coord(w.posY[s])).toDouble()
                if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
            }
            if ((maxy - miny) > (maxx - minx)) { axisX = 0.0; axisY = 1.0 }
        }

        measure(0)
        for (t in 1..ticks) {
            w = soa.tick(w, CytoInput.EMPTY)
            if (!measure(t)) { sb.appendLine(">>> organism gone at tick $t"); break }
        }

        // ── coarse COM trajectory over the whole run (does it go anywhere?) ──
        sb.appendLine("tick\tCOM(x,y)\tdriftFromStart\tcomVel\tmeanR\tstdR\thalfA\thalfB")
        val every = (ticks / 20).coerceAtLeast(1)
        for (t in 0..ticks step every) {
            val d = hypot(comX[t] - comX[0], comY[t] - comY[0])
            sb.appendLine("$t\t(${f(comX[t])},${f(comY[t])})\t${f(d)}\t${f4(comVel[t])}\t${f(meanR[t])}\t${f4(stdR[t])}\t${f(halfA[t])}\t${f(halfB[t])}")
        }

        // ── summary discriminators ──
        // use the second half of the run (skip load transient)
        val w0 = ticks / 2
        fun amp(a: DoubleArray): Double { var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; for (t in w0..ticks) { if (a[t] < lo) lo = a[t]; if (a[t] > hi) hi = a[t] }; return hi - lo }
        fun mean(a: DoubleArray): Double { var s = 0.0; for (t in w0..ticks) s += a[t]; return s / (ticks - w0 + 1) }
        fun corr(a: DoubleArray, b: DoubleArray): Double {
            val ma = mean(a); val mb = mean(b); var num = 0.0; var da = 0.0; var db = 0.0
            for (t in w0..ticks) { val x = a[t] - ma; val y = b[t] - mb; num += x * y; da += x * x; db += y * y }
            return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
        }
        val netDrift = hypot(comX[ticks] - comX[0], comY[ticks] - comY[0])
        var peakDrift = 0.0; for (t in 0..ticks) { val d = hypot(comX[t] - comX[0], comY[t] - comY[0]); if (d > peakDrift) peakDrift = d }
        val rAmp = amp(meanR); val rStd = mean(stdR); val halfCorr = corr(halfA, halfB)
        var peakComVel = 0.0; for (t in w0..ticks) if (comVel[t] > peakComVel) peakComVel = comVel[t]
        var peakSpeed = 0.0; for (t in w0..ticks) if (maxSpeed[t] > peakSpeed) peakSpeed = maxSpeed[t]
        var peakPosMotion = 0.0; for (t in w0..ticks) if (posMotion[t] > peakPosMotion) peakPosMotion = posMotion[t]
        val meanPosMotion = mean(posMotion)
        val bodySize = run {
            val slots = ArrayList<Int>(); for (id in idSet) { val s = w.slotOf(id); if (s in 0 until w.count) slots.add(s) }
            var minx = Double.MAX_VALUE; var maxx = -Double.MAX_VALUE
            for (s in slots) { val x = CytoUnits.toLogical(Coord(w.posX[s])).toDouble(); if (x < minx) minx = x; if (x > maxx) maxx = x }
            maxx - minx
        }

        sb.appendLine()
        sb.appendLine("=== VERDICT (window = ticks $w0..$ticks) ===")
        sb.appendLine("organism cells (alive at end): $alive   body x-extent ~ ${f(bodySize)} cell-diameters")
        sb.appendLine("NET COM drift over $ticks ticks : ${f(netDrift)} cell-diameters   (peak excursion ${f(peakDrift)})")
        sb.appendLine("contraction — mean-radius temporal amplitude: ${f4(rAmp)}   (mean radius ${f(mean(meanR))})")
        sb.appendLine("spatial radius spread (std across body, mean): ${f4(rStd)}")
        sb.appendLine("two-halves radius correlation over time     : ${f4(halfCorr)}   (+1 = in-phase BREATH, -1 = anti-phase WAVE)")
        sb.appendLine("peak COM speed (mass-weighted)              : ${f4(peakComVel)} cell-diam/tick")
        sb.appendLine("--- the channel test ---")
        sb.appendLine("real positional shape change |Δpos/COM|/tick: mean ${f4(meanPosMotion)}, peak ${f4(peakPosMotion)} cell-diam/tick")
        sb.appendLine("VELOCITY-channel single-cell speed (velX/Y) : peak ${f4(peakSpeed)} cell-diam/tick")
        sb.appendLine("  -> if cells MOVE positionally but velocity~0, the motion is in the spring POSITION-CORRECTION channel,")
        sb.appendLine("     which the drag system (reads velX/velY only) CANNOT SEE -> no thrust regardless of phasing.")
        sb.appendLine()
        val contracting = rAmp > 0.02
        val phased = rStd > rAmp * 0.5 || halfCorr < 0.3
        sb.appendLine("read: " + when {
            !contracting -> "NOT CONTRACTING — the actuator isn't moving the body; nothing to convert to thrust."
            !phased -> "SYNCHRONISED BREATH — strong in-phase pulsing, ~no spatial phase. Reciprocal stroke: by symmetry the asymmetric drag nets to ~0, so the COM cannot translate (scallop theorem). This is a DESIGN/CONTROLLER gap, not a drag bug."
            else -> "PHASED — there IS spatial phase in the contraction; if drift is still ~0, the drag asymmetry may not be converting the wave to thrust (worth a drag look)."
        })

        java.io.File("/tmp/swimprobe.txt").writeText(sb.toString())
        println(sb)
    }

    private fun f(x: Double) = (kotlin.math.round(x * 100) / 100.0).toString()
    private fun f4(x: Double) = (kotlin.math.round(x * 10000) / 10000.0).toString()
}
