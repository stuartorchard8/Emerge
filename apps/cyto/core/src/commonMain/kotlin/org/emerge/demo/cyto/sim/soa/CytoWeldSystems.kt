package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.soa.ColumnPartition
import org.emerge.sim.core.ecs.soa.SoaSystem
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.math.max
import kotlin.math.min

/** Connections phase — spring stress + damage + break. The heavy per-edge stress/damage compute (loop 2)
 *  is per-directed-edge and writes only that edge's own CSR columns, so it partitions by cell via
 *  [ColumnPartition.detectThenApply]: workers compute + write their own edges' columns and emit the keys
 *  they'd break, then the break keys are unioned serially and pruned. The `pairDmg` min-map (loop 1) stays
 *  serial (cheap, and read-only during loop 2). Bit-identical to the sequential sweep. */
class ConnectionsSystem(
    private val state: CytoPipelineState,
    private val executor: ParallelExecutor?,
    private val parallelThreshold: Int,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val pairDmg = state.connPairDmg.also { it.clear() }
        for (i in 0 until world.count) {
            for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                if (world.csr.otherSlot[k] < 0) continue
                val key = pairKey(world.entityId[i], world.csr.otherId[k])
                val cur = world.csr.edgeAux[k]
                val prev = pairDmg[key]
                if (prev == null || cur < prev) pairDmg[key] = cur
            }
        }

        val collinearPeriod = cfg.weldCollinearCheckPeriod.coerceAtLeast(1)
        val scanCollinear = collinearPeriod == 1 || world.world.tick % collinearPeriod == 0L
        val broken = HashSet<Long>()
        ColumnPartition.detectThenApply(
            world.count, executor, parallelThreshold,
            detect = { start, end, out ->
                for (i in start until end) {
                    val radiusA = world.radiusRaw[i]
                    for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                        val nSlot = world.csr.otherSlot[k]
                        if (nSlot < 0) continue
                        val rest = radiusA + world.radiusRaw[nSlot]
                        val restLogical = CytoUnits.toLogical(Frac(rest))
                        val dist = deltaLen(world, i, nSlot)
                        val stretch = CytoUnits.toLogical(dist) - restLogical
                        val deg = maxOf(world.csr.degreeOf(i), world.csr.degreeOf(nSlot))
                        val tension = max(0f, stretch * cfg.connectionStressScale) / (1 shl deg.coerceAtMost(20))

                        val breakDist = cfg.overStretchBreakMultiple * restLogical
                        val overStretch = if (stretch > 0f && breakDist > 0f) {
                            val ratio = stretch / breakDist
                            var p = 1f
                            repeat(cfg.overStretchDamageExponent) { p *= ratio }
                            cfg.connectionBreakDamage * p
                        } else 0f

                        val compression = max(0f, -stretch - cfg.compressionTolerance) * cfg.connectionStressScale

                        val collinear = if (scanCollinear && world.csr.degreeOf(i) >= 2 && world.csr.degreeOf(nSlot) >= 2 &&
                            throughCellChord(world, i, nSlot, cfg)) cfg.weldCollinearDamage * collinearPeriod else 0f

                        val stress = tension + overStretch + compression + collinear
                        val key = pairKey(world.entityId[i], world.csr.otherId[k])
                        val damage = max(0f, (pairDmg[key] ?: world.csr.edgeAux[k]) + stress)
                        if (damage > cfg.connectionBreakDamage) {
                            out.add(key)
                        } else {
                            world.csr.restRaw[k] = rest
                            world.csr.stiffRaw[k] = cfg.springStiffness.raw
                            world.csr.dampRaw[k] = cfg.springDamping.raw
                            world.csr.edgeAux[k] = damage
                        }
                    }
                }
            },
            apply = { key -> broken.add(key) },
        )
        if (broken.isEmpty()) return
        pruneEdges(world, broken)
    }
}

/** Drag force phase — viscous drag. Per-cell disjoint: each cell writes only its own [impVel]; neighbour
 *  positions/velocities are read-only, so it partitions bit-identically. */
class DragSystem(
    private val executor: ParallelExecutor?,
    private val parallelThreshold: Int,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val grabbed = (inputs.values.firstOrNull() as? CytoInput)?.grab?.entity?.value ?: -1
        ColumnPartition.disjoint(world.count, executor, parallelThreshold) { start, end ->
          for (i in start until end) {
            if (world.entityId[i] == grabbed) continue
            var exposed = Coord2(Coord(world.velX[i]), Coord(world.velY[i])).asFrac2()
            val pos = Coord2(Coord(world.posX[i]), Coord(world.posY[i]))
            for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                val nSlot = world.csr.otherSlot[k]; if (nSlot < 0) continue
                val normal = (Coord2(Coord(world.posX[nSlot]), Coord(world.posY[nSlot])) - pos).norm
                val toward = exposed.dot(normal)
                if (toward.raw > 0L) exposed -= normal * toward
            }
            val speed = CytoUnits.toLogical(exposed.len)
            if (speed == 0f) continue
            val radius = Frac(world.cell.logicalRadius[i]).toFloat()
            val surfaceDrag = cfg.dragCoefficient * speed * speed
            val widthDrag = cfg.cellWidthDragCoefficient * radius * speed
            val dragSpeed = min(cfg.dragMaxFraction * speed, surfaceDrag + widthDrag)
            val impulse = exposed.norm * CytoUnits.len(-dragSpeed)
            world.impVelX[i] += impulse.x.raw; world.impVelY[i] += impulse.y.raw
          }
        }
    }
}

/** Spring constraint solve phase — parallel per-body gather Jacobi. */
class SpringSolveSystem(
    private val executor: ParallelExecutor?,
    private val springParallelThreshold: Int,
    private val state: CytoPipelineState,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val n = world.count
        if (n == 0) return
        state.ensureSpringScratch(n)
        val p0x = state.ssP0x; val p0y = state.ssP0y; val px = state.ssPx; val py = state.ssPy
        val bvx = state.ssBvx; val bvy = state.ssBvy; val vx = state.ssVx; val vy = state.ssVy
        val mass = state.ssMass; val dx = state.ssDx; val dy = state.ssDy
        val csr = world.csr

        var anySpring = false
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            anySpring = true
            val pxr = world.posX[i].toLong(); val pyr = world.posY[i].toLong()
            p0x[i] = pxr; p0y[i] = pyr; px[i] = pxr; py[i] = pyr
            bvx[i] = world.velX[i].toLong(); bvy[i] = world.velY[i].toLong()
            vx[i] = bvx[i] + world.impVelX[i]; vy[i] = bvy[i] + world.impVelY[i]
            mass[i] = world.mass[i].toUInt().toLong()
        }
        if (!anySpring) return

        // Precompute per-edge normals and mass weights
        val edges = csr.offset[n]
        state.ensureEdgeScratch(edges)
        val enX = state.ssEnX; val enY = state.ssEnY; val ew = state.ssEw
        ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
            for (i in start until end) {
                if (csr.degreeOf(i) == 0) continue
                val mi = mass[i]; val p0ix = p0x[i]; val p0iy = p0y[i]
                for (k in csr.offset[i] until csr.offset[i + 1]) {
                    val j = csr.otherSlot[k]
                    if (j < 0) { enX[k] = 0L; enY[k] = 0L; ew[k] = 0L; continue }
                    val total = mi + mass[j]
                    ew[k] = if (total <= 0L) 0L else mass[j] * FRAC_MAX / total.toInt().toLong()
                    val ddx = (p0x[j].toInt() - p0ix.toInt()).toLong(); val ddy = (p0y[j].toInt() - p0iy.toInt()).toLong()
                    val dist = lenRaw(ddx, ddy)
                    if (dist == 0L) { enX[k] = 0L; enY[k] = 0L } else {
                        enX[k] = ddx * FRAC_MAX / dist; enY[k] = ddy * FRAC_MAX / dist
                    }
                }
            }
        }

        // Velocity solve
        repeat(ITERATIONS) {
            ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
                for (i in start until end) {
                    if (csr.degreeOf(i) == 0) continue
                    var accX = 0L; var accY = 0L
                    val vix = vx[i]; val viy = vy[i]
                    for (k in csr.offset[i] until csr.offset[i + 1]) {
                        val j = csr.otherSlot[k]; if (j < 0) continue
                        val nx = enX[k]; val ny = enY[k]
                        val rvx = vx[j] - vix; val rvy = vy[j] - viy
                        val relVel = rvx * nx / FRAC_MAX + rvy * ny / FRAC_MAX
                        val vCorr = relVel * csr.dampRaw[k] / FRAC_MAX
                        val scalar = vCorr * ew[k] / FRAC_MAX
                        accX += nx * scalar / FRAC_MAX; accY += ny * scalar / FRAC_MAX
                    }
                    dx[i] = accX; dy[i] = accY
                }
            }
            for (i in 0 until n) { if (csr.degreeOf(i) == 0) continue; vx[i] += dx[i]; vy[i] += dy[i] }
        }

        // Position solve
        val compStiffMul = cfg.weldCompressionStiffnessMultiple
        repeat(ITERATIONS) {
            ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
                for (i in start until end) {
                    if (csr.degreeOf(i) == 0) continue
                    var accX = 0L; var accY = 0L
                    val pix = px[i]; val piy = py[i]
                    for (k in csr.offset[i] until csr.offset[i + 1]) {
                        val j = csr.otherSlot[k]; if (j < 0) continue
                        val ddx = (px[j].toInt() - pix.toInt()).toLong()
                        val ddy = (py[j].toInt() - piy.toInt()).toLong()
                        val dist = lenRaw(ddx, ddy); if (dist == 0L) continue
                        val nx = ddx * FRAC_MAX / dist; val ny = ddy * FRAC_MAX / dist
                        val lengthError = dist - csr.restRaw[k]
                        var pCorr = lengthError * csr.stiffRaw[k] / FRAC_MAX
                        if (lengthError < 0L) pCorr *= compStiffMul
                        val scalar = pCorr * ew[k] / FRAC_MAX
                        accX += nx * scalar / FRAC_MAX; accY += ny * scalar / FRAC_MAX
                    }
                    dx[i] = accX; dy[i] = accY
                }
            }
            for (i in 0 until n) { if (csr.degreeOf(i) == 0) continue; px[i] += dx[i]; py[i] += dy[i] }
        }

        // Emit impulses
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            world.impVelX[i] = vx[i] - bvx[i]; world.impVelY[i] = vy[i] - bvy[i]
            world.impPosX[i] += px[i] - p0x[i]; world.impPosY[i] += py[i] - p0y[i]
        }
    }
}
