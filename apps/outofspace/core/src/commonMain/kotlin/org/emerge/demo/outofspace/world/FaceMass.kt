package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species

/**
 * How much mass sits on a face, and how much sits in a tile.
 *
 * A face is not a place mass is *stored* — mass lives in tiles — but momentum lives on faces, and
 * momentum divided by mass is the velocity everything else asks for. So a face needs a mass, and the
 * only sensible one is the mean of the tiles it separates: the face is the boundary between them, and
 * half of each is what a control volume drawn around it would contain.
 *
 * A face on the rim of the grid averages only the tile it actually has. Counting the vacuum beyond as
 * a real half-cell of nothing would halve the mass and so read every escaping draught at double
 * speed — which is precisely the flow that becomes thrust, so it is the one place the error would
 * matter most.
 *
 * Shared rather than duplicated because [MomentumField] and the advection passes must agree about
 * this exactly. Two slightly different notions of a face's mass would show up as momentum quietly
 * appearing at the boundary, which is the one failure the whole scheme is arranged to make
 * impossible.
 */
internal fun xFaceGrams(edges: EdgeGrid, tileGrams: LongArray, edge: Int): Long =
    meanOf(tileGrams, edges.xEdgeBefore(edge), edges.xEdgeAfter(edge))

internal fun yFaceGrams(edges: EdgeGrid, tileGrams: LongArray, edge: Int): Long =
    meanOf(tileGrams, edges.yEdgeBefore(edge), edges.yEdgeAfter(edge))

private fun meanOf(tileGrams: LongArray, before: Int, after: Int): Long {
    var sum = 0L
    var count = 0
    if (before >= 0) { sum += tileGrams[before]; count++ }
    if (after >= 0) { sum += tileGrams[after]; count++ }
    return if (count == 0) 0L else sum / count
}

/** Total mass of the given all species in each tile — the density field everything else reads. */
fun tileMass(tileCount: Int, grams: LongArray): LongArray =
    LongArray(tileCount) { tile ->
        var sum = 0L
        val base = tile * Species.COUNT
        for (s in Species.ALL) sum += grams[base + s.ordinal]
        sum
    }
