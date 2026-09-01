package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * Two bodies welded into one — `PLAN_economy.md` §7.
 *
 * A dock is the simplest joint there is: the two members hold a fixed relative pose for ever, so the
 * pair is a single rigid body and needs no solver, no constraint and no iteration. What it does need
 * is the pair's **centre of mass** and the pair's **radius of gyration**, because those are what
 * decide where the pair turns about and how reluctantly.
 *
 * ### ⛔ Every quantity here is a difference
 *
 * The obvious way to combine two distributions is `Σ m·x` over both, and it is the one thing this
 * file may not do: `Σ m·x` at a microgram per unit is 2e17 on a heavy grid in *tiles* and 2e20 in
 * millitiles, and it is worst for the vessel furthest from the world origin — which is every vessel,
 * eventually. So the joint centre is computed as an **offset from one member's centre**, where the
 * lever arm is bounded by the size of the pair rather than by where the pair happens to be. The same
 * argument [massDistribution] makes when it refuses the parallel-axis shortcut; the difference is
 * that here the parallel-axis term is safe, because here the distance is between two things that are
 * touching.
 *
 * [scaledRatio] carries every product that could still be large: it reduces before it scales, which
 * removes the exponent rather than raising the bound.
 */
object Composite {

    /**
     * The mass distribution of [a] and [b] welded together, in **[a]'s frame**.
     *
     * [bOffsetX]/[bOffsetY] are where `b`'s centre of mass sits relative to `a`'s, in millitiles, in
     * world axes. The result's centre is likewise **relative to `a`'s centre**, not absolute — there
     * is no absolute answer that is safe to compute and none that a caller needs.
     */
    fun combined(
        a: MassDistribution,
        b: MassDistribution,
        bOffsetX: Long,
        bOffsetY: Long,
    ): Joint {
        val total = a.mass + b.mass
        if (total <= 0L) return Joint(MassDistribution.EMPTY, 0L, 0L)
        if (a.mass <= 0L) return Joint(b, bOffsetX, bOffsetY)
        if (b.mass <= 0L) return Joint(a, 0L, 0L)

        // Where the joint centre sits, measured from a's centre: b's offset weighted by b's share.
        // ⚠️ `b.mass × offset` overflows outright at these units; the ratio must be reduced first.
        val jointX = scaledRatio(bOffsetX, total, b.mass)
        val jointY = scaledRatio(bOffsetY, total, b.mass)

        // Parallel axis, per member, about the joint centre. `d²` in millitiles² is already in
        // GYRATION_SCALE-ths of a tile², because a millitile² *is* a millionth of a tile² — the two
        // scales coincide, which is why this needs no conversion and is worth saying out loud.
        val aDx = -jointX
        val aDy = -jointY
        val bDx = bOffsetX - jointX
        val bDy = bOffsetY - jointY
        val aTerm = a.gyrationSq + aDx * aDx + aDy * aDy
        val bTerm = b.gyrationSq + bDx * bDx + bDy * bDy

        // `m × (g + d²) / M` per member. Each is bounded by its own bracket because `m ≤ M`.
        val gyrationSq = scaledRatio(a.mass, total, aTerm) + scaledRatio(b.mass, total, bTerm)

        return Joint(
            MassDistribution(
                mass = total,
                comMilliX = a.comMilliX + jointX,
                comMilliY = a.comMilliY + jointY,
                // The joint offset is a millitile quantity — it is a lever arm, and the parallel-axis
                // term above needs it to be one — so the pair's centre carries `a`'s full precision
                // plus that arm, and no more. There is no finer answer to be had here.
                comX = a.comX + jointX * Rotation.PER_MILLI_TILE,
                comY = a.comY + jointY * Rotation.PER_MILLI_TILE,
                gyrationSq = gyrationSq,
            ),
            jointX,
            jointY,
        )
    }

    /**
     * The pair's distribution, and where its centre sits relative to the first member's.
     *
     * The offset is returned rather than recovered from [MassDistribution.comX], because that field
     * is in the first member's own frame and a caller advancing the pair needs the pivot in world
     * axes — the two differ by the member's pose the moment anything is turned.
     */
    class Joint(val about: MassDistribution, val offsetX: Long, val offsetY: Long)

    /** Millitiles to [Flight.PER_TILE]s — the two length scales this file has to move between. */
    const val PER_MILLI_TILE: Long = Rotation.PER_MILLI_TILE
}
