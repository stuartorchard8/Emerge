package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.ecs.soa.SoaSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

// ──────────────────────────────────────────────────────────────────────────────
// SoA System implementations for each phase
// ──────────────────────────────────────────────────────────────────────────────

/** Reset phase — zero the dense impulse accumulator. */
class ResetSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val n = world.count
        for (i in 0 until n) {
            world.impPosX[i] = 0L; world.impPosY[i] = 0L
            world.impVelX[i] = 0L; world.impVelY[i] = 0L; world.impAngVel[i] = 0L
        }
    }
}

/** Contacts phase — broadphase contact detection + resolve. Produces weldLo/weldHi. */
class ContactsSystem(
    private val executor: ParallelExecutor?,
    private val state: CytoPipelineState,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        state.weldLo.clear(); state.weldHi.clear()
        val n = world.count
        if (state.touchScratch.size < n) state.touchScratch = IntArray(n) else state.touchScratch.fill(0, 0, n)
        if (state.touchingScratch.size < n) state.touchingScratch = Array(n) { ArrayList() } else for (i in 0 until n) state.touchingScratch[i].clear()
        if (n < 2) return

        // Compute grid dimensions
        var maxRadius = 0L
        for (i in 0 until n) if (world.radiusRaw[i] > maxRadius) maxRadius = world.radiusRaw[i]
        if (maxRadius <= 0L) return

        val dims = SpatialGrid.packedDimsFor(
            minCellSize = maxRadius * 2L,
            maxCellsPerAxisLog2 = SpatialGrid.cellsPerAxisLog2For(n),
        )
        if (dims < 0L) return

        val cached = state.contactGrid
        val grid = if (cached != null && cached.packedDims == dims) {
            cached.clearForReuse(); cached
        } else {
            SpatialGrid.ofPackedDims(dims).also { state.contactGrid = it }
        }

        for (i in 0 until n) grid.insert(i, world.posX[i], world.posY[i])

        // Sequential single pass in (i asc, j asc) order
        var scratch = IntArray(16)
        for (i in 0 until n) {
            val aX = world.posX[i]; val aY = world.posY[i]; val aR = world.radiusRaw[i]
            var cc = 0
            grid.forEachNeighbour(aX, aY) { j ->
                if (j > i) {
                    val sum = aR + world.radiusRaw[j]
                    val dx = longAbs((aX - world.posX[j]).toLong())
                    val dy = longAbs((aY - world.posY[j]).toLong())
                    if (dx < sum && dy < sum) {
                        if (cc >= scratch.size) scratch = scratch.copyOf(scratch.size * 2)
                        scratch[cc] = j; cc += 1
                    }
                }
            }
            insertionSort(scratch, cc)
            for (k in 0 until cc) {
                val j = scratch[k]
                // Spring-connected pairs produce no contact effect — skip them before Contact.compute
                if (edgeExists(world, i, world.entityId[j]) || edgeExists(world, j, world.entityId[i])) continue
                val contact = Contact.compute(
                    aId = EntityId(world.entityId[i]), bId = EntityId(world.entityId[j]),
                    aTransform = transformAt(world, i), bTransform = transformAt(world, j),
                    aRadius = Frac(aR), bRadius = Frac(world.radiusRaw[j]),
                ) ?: continue
                handleContact(world, i, j, contact, cfg, state)
            }
        }
    }
}

/** Grab force phase. */
class GrabSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val g = (inputs.values.firstOrNull() as? CytoInput)?.grab ?: return
        val slot = world.slotOf(g.entity.value); if (slot < 0) return
        val pos = Coord2(Coord(world.posX[slot]), Coord(world.posY[slot]))
        val vel = Coord2(Coord(world.velX[slot]), Coord(world.velY[slot])).asFrac2()
        val target = CytoUnits.coord2(g.x, g.y)
        val toTarget = target - pos
        val maxReach = CytoUnits.len(cfg.grabMaxReach)
        val reach = if (toTarget.len > maxReach) toTarget.norm * maxReach else toTarget
        val pull = reach * cfg.grabStiffness - vel * cfg.grabDamping
        world.impVelX[slot] += pull.x.raw; world.impVelY[slot] += pull.y.raw
        if (g.sticky) world.cell.stickyTemp[slot] = true
    }
}

/** Integration phase. */
class IntegrateSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        for (i in 0 until world.count) {
            val transform = transformAt(world, i)
            val motion = MotionComponent(Coord2(Coord(world.velX[i]), Coord(world.velY[i])), Coord(world.angVel[i]))
            val impulse = ImpulseComponent(
                pos = Frac2(Frac(world.impPosX[i]), Frac(world.impPosY[i])),
                vel = Frac2(Frac(world.impVelX[i]), Frac(world.impVelY[i])),
                angVel = Frac(world.impAngVel[i]),
            )
            val vel = motion.vel + impulse.vel
            val pos = transform.pos + impulse.pos + vel.asFrac2()
            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel / 2
            val angVel = motion.angVel + impulse.angVel
            world.posX[i] = pos.x.raw; world.posY[i] = pos.y.raw; world.ang[i] = ang.raw
            world.velX[i] = pos.x.raw - transform.pos.x.raw
            world.velY[i] = pos.y.raw - transform.pos.y.raw
            world.angVel[i] = angVel.raw
        }
    }
}
