package org.emerge.demo.outofspace.world

/**
 * Total mass of all species in each tile — the density field everything else reads.
 *
 * ⛔ This is what is left of `FaceMass.kt`. That file also answered *how much mass sits on a face*,
 * for the momentum that used to live on faces; momentum does not live there any more — the hull's
 * reaction moved to the vessel boundary, where only mass that genuinely leaves may push — so the
 * face half went with the per-edge field it existed to serve.
 */
fun tileMass(tileCount: Int, masses: MassArray): LongArray =
    LongArray(tileCount) {
        var sum = 0L
        masses.forEachFluid(TileIndex(it)) { _, mass -> sum += mass }
        sum
    }
