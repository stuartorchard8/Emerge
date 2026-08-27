package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.condensedDensityAt
import org.emerge.demo.outofspace.chem.criticalOf
import org.emerge.demo.outofspace.chem.reducedDensity
import org.emerge.demo.outofspace.chem.vapourMass
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * **Frost and puddles fall.**
 *
 * [diffuseFluid] moves the vapour and leaves the condensed phase exactly where it froze, and that is
 * right: Fick's law describes a mixture spreading through itself, and a pool under an atmosphere is
 * two phases with an interface — differencing across it dissolves the pool (measured: 76% of a
 * saturated pool gone in twenty ticks). But "does not diffuse" is not "does not move". A puddle in a
 * ship under thrust runs to the back of the room, and in a spun ring it lies against the rim.
 *
 * So the condensed phase gets its own transport, and it is not diffusion at all: it does not spread,
 * it **goes downhill**. Which way is downhill is [feltGravity] plus [centrifugalAt] at that cell —
 * the same field the gas drift reads, so the two cannot disagree about which way is down.
 *
 * ⛔ **In freefall nothing settles, and that is the point rather than a limitation.** A ship with no
 * plating, no burn and no spin has no down, so its frost hangs where it formed. Every scrap of
 * motion here is subjective gravity; there is no term that acts without one.
 *
 * ⚠️ **Jacobi, like the rest of the tick.** Every cell is judged against the *starting* state and
 * the moves are applied at the end, so a column drains one cell per firing rather than collapsing in
 * a single pass. Sequential bottom-up would drain faster and would make the answer depend on the
 * order the tiles happen to be walked, which is the one thing a lockstep sim may not have.
 */
fun settleCondensate(
    edges: EdgeGrid,
    apertures: ApertureField,
    masses: MassArray,
    energies: EnergyArray?,
    kelvin: IntArray,
    feltGravity: Frac2,
    spin: Long,
    about: MassDistribution,
    /** What share of a cell's condensate moves per firing, in permille. */
    sharePermille: Int = SETTLE_PERMILLE,
): SettleStep {
    val grid = edges.grid
    // ⛔ **No down, nothing to do — and answered before allocating anything.** Every unit of motion
    // here is subjective gravity, so a coasting hull with no plating, no burn and no spin settles
    // nothing at all. That is the common case in flight, and the two arrays below are a **dense
    // 1.9 MiB `MassArray`** and its energy twin: worth not building to discover they stay empty.
    if (feltGravity.x.raw == 0L && feltGravity.y.raw == 0L && (spin == 0L || about.mass <= 0L)) {
        return SettleStep(0L, 0L)
    }
    val delta = MassArray(grid.size)
    val deltaEnergy = if (energies == null) null else EnergyArray(grid.size)
    val startingMass = tileMass(grid.size, masses)
    var ventedMass = 0L
    var ventedEnergy = 0L

    for (i in 0 until grid.size) {
        val tile = TileIndex(i)
        val own = startingMass[i]
        if (own <= 0L) continue

        // Which way is down here — the uniform field plus this cell's own centrifugal arm.
        var downX = feltGravity.x.raw
        var downY = feltGravity.y.raw
        if (spin != 0L && about.mass > 0L) {
            val spun = centrifugalAt(
                spin,
                tileCentre(grid.xOf(tile)) - about.comX,
                tileCentre(grid.yOf(tile)) - about.comY,
            )
            downX += spun.x.raw
            downY += spun.y.raw
        }
        if (downX == 0L && downY == 0L) continue

        // ⚠️ **One direction, the steeper axis.** A grid has four ways out and gravity has an angle;
        // splitting the fall between two faces by the components would be smoother, and it would
        // also let a cell shed twice as much per firing as [sharePermille] says. The steeper axis
        // keeps the rate honest, and the stair-stepping it produces on a diagonal is what a lattice
        // looks like when something slides down it.
        val alongX = if (downX < 0L) -downX else downX
        val alongY = if (downY < 0L) -downY else downY
        val face: Int
        val edge: Int
        val neighbour: TileIndex
        if (alongX >= alongY) {
            if (downX > 0L) {
                edge = edges.rightEdgeOf(tile); neighbour = edges.xEdgeAfter(edge)
            } else {
                edge = edges.leftEdgeOf(tile); neighbour = edges.xEdgeBefore(edge)
            }
            face = if (apertures.xAt(edge) > 0) apertures.xAt(edge) else 0
        } else {
            if (downY > 0L) {
                edge = edges.downEdgeOf(tile); neighbour = edges.yEdgeAfter(edge)
            } else {
                edge = edges.upEdgeOf(tile); neighbour = edges.yEdgeBefore(edge)
            }
            face = if (apertures.yAt(edge) > 0) apertures.yAt(edge) else 0
        }
        if (face <= 0) continue

        // The two ways *across* the fall, for a liquid that finds its road blocked — see below.
        val lateral0: TileIndex
        val lateral1: TileIndex
        val lateralFace0: Int
        val lateralFace1: Int
        if (alongX >= alongY) {
            val up = edges.upEdgeOf(tile)
            val down = edges.downEdgeOf(tile)
            lateral0 = edges.yEdgeBefore(up); lateralFace0 = apertures.yAt(up)
            lateral1 = edges.yEdgeAfter(down); lateralFace1 = apertures.yAt(down)
        } else {
            val left = edges.leftEdgeOf(tile)
            val right = edges.rightEdgeOf(tile)
            lateral0 = edges.xEdgeBefore(left); lateralFace0 = apertures.xAt(left)
            lateral1 = edges.xEdgeAfter(right); lateralFace1 = apertures.xAt(right)
        }

        var moved = 0L
        masses.forEachFluid(tile) { fluid, count ->
            // Only the part that is *not* vapour: the vapour is diffusion's business and moving it
            // here as well would give it two transports and double its speed downhill.
            val condensed = count - vapourMass(count, fluid.species, VolumeField.FULL, VolumeField.FULL, kelvin[i])
            if (condensed <= 0L) return@forEachFluid
            var out = condensed / 1000L * sharePermille
            if (face < ApertureField.OPEN) out = out * face / ApertureField.OPEN
            if (out <= 0L) return@forEachFluid
            // The same refusal the vapour meets — a cell already at this species' condensed
            // density takes no more of it. Without it, downhill is somewhere frost can be poured
            // for ever.
            val blocked = neighbour != TileIndex.NONE &&
                isPackedWith(masses, neighbour, fluid, kelvin[neighbour.index])
            if (!blocked) {
                delta.add(tile, fluid, -out)
                if (neighbour == TileIndex.NONE) ventedMass += out else delta.add(neighbour, fluid, out)
                moved += out
                return@forEachFluid
            }

            // ── Blocked, and here is where a liquid stops behaving like a solid ──
            //
            // ⛔ **A liquid that cannot go down goes sideways; a solid stays put and piles.** That
            // one difference is the whole of what separates the two in this pass, and it is what
            // makes a liquid read as *filling its container* while a heap of frost reads as a heap.
            // Both still respect the cell they are moving into — neither may over-pack one.
            //
            // ⚠️ **Only into a cell holding less of it than this one.** Spreading into a fuller
            // neighbour is flowing uphill, and without the test a puddle would slosh across a level
            // floor for ever instead of coming to rest on it. The comparison is what makes this
            // levelling rather than mixing.
            if (isSolidAt(fluid, kelvin[i])) return@forEachFluid
            val here = masses[MassIndex(tile, fluid)]
            var spread = 0L
            for (side in 0 until 2) {
                val to = if (side == 0) lateral0 else lateral1
                val faceOpen = if (side == 0) lateralFace0 else lateralFace1
                if (to == TileIndex.NONE || faceOpen <= 0) continue
                if (masses[MassIndex(to, fluid)] >= here) continue
                if (isPackedWith(masses, to, fluid, kelvin[to.index])) continue
                var sideways = out / 2L
                if (faceOpen < ApertureField.OPEN) sideways = sideways * faceOpen / ApertureField.OPEN
                if (sideways <= 0L) continue
                delta.add(tile, fluid, -sideways)
                delta.add(to, fluid, sideways)
                spread += sideways
            }
            moved += spread
        }

        if (moved > 0L && energies != null && deltaEnergy != null) {
            val carried = scaledRatio(moved, own, energies[tile])
            deltaEnergy[tile] -= carried
            if (neighbour == TileIndex.NONE) ventedEnergy += carried else deltaEnergy[neighbour] += carried
        }
    }

    for (t in grid.tiles) delta.forEachFluid(t) { fluid, d -> masses.add(t, fluid, d) }
    if (energies != null && deltaEnergy != null) for (t in grid.tiles) energies[t] += deltaEnergy[t]
    return SettleStep(ventedMass, ventedEnergy)
}

/** What one settling pass sent over the rim — the same two ledgers [DiffusionStep] reports. */
class SettleStep(val ventedMass: Long, val ventedEnergy: Long)

/**
 * How much of a cell's condensate goes downhill per firing, in permille.
 *
 * ⚠️ A rate and not a speed. Frost does not accelerate: a heap shifts a share of itself per firing
 * and the heap in front of it does the same, so what a column does is drain rather than fall. That
 * is what a lattice with no per-tile velocity can honestly claim, and it is the same shape
 * [SLOTS] gives the vapour.
 */
const val SETTLE_PERMILLE = 250

/**
 * Is this species **solid** at [kelvin], rather than liquid?
 *
 * ⚠️ **Below the triple point, not `phaseAt == Solid`.** The dome is a vapour-liquid construction and
 * has no solid branch at all, so the phase it reports below the triple point is an extrapolation of
 * the wrong curve. The triple point is the honest line and it is the one the rest of the matter-state
 * code already uses. A species with no critical point on file has no triple point either, and reads
 * as a liquid — which is the forgiving answer, since it only decides whether it may spread sideways.
 */
private fun isSolidAt(fluid: Fluid, kelvin: Int): Boolean {
    val triple = criticalOf(fluid.species)?.triplePointKelvin ?: return false
    return kelvin < triple
}

/** [isPacked]'s question, reachable from here — see [diffuseFluid]. */
private fun isPackedWith(masses: MassArray, tile: TileIndex, fluid: Fluid, kelvin: Int): Boolean {
    val held = masses[MassIndex(tile, fluid)]
    if (held <= 0L) return false
    val limit = condensedDensityAt(kelvin, fluid.species) ?: return false
    val density = reducedDensity(held, fluid.species, VolumeField.FULL, VolumeField.FULL) ?: return false
    return density >= limit
}
