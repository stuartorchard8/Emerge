package org.emerge.demo.norns.morph

import kotlin.math.hypot
import kotlin.math.max

/**
 * One node of a creature's **morphology genome** — a part/limb attached to its parent. Ported to 2D
 * from Stu's `evolutionism` Unity prototype, preserving its core ideas: the genome *is* a tree of
 * named parametric nodes; nesting = the limb hierarchy; [name] is a **stable identity** used to align
 * homologous parts across two genomes when crossbreeding (head↔head, not by position) and is what
 * lets alignment survive structural mutation (new nodes get fresh names). Geometry is fully
 * parametric — there is no per-part art reference here; how a node is *drawn* is a separate concern.
 *
 * Geometry (the 2D analog of evolutionism's 3D version):
 *  - [ox],[oy]: attach offset from the parent, in the parent's local frame — direction = where the
 *    limb points, magnitude = bone length. Neutral/forward is +Y.
 *  - [scale]: multiplies the parent's scale (compounds down the tree).
 *  - [sym]: radial symmetry count (e.g. 5 → five copies spaced around the attach point).
 *  - [mirX],[mirY]: a mirror-axis normal; |mirror| ≥ 1 bilaterally duplicates this node + its subtree
 *    (how one gene yields a left/right pair).
 *  - [extra]: any further per-part params (appearance — colour, width, curvature, … — added once the
 *    art representation is chosen). Blended generically on crossbreed.
 */
class MorphNode(
    var name: String,
    var ox: Float = 0f,
    var oy: Float = 0f,
    var scale: Float = 1f,
    var sym: Int = 0,
    var mirX: Float = 0f,
    var mirY: Float = 0f,
    val extra: MutableMap<String, Float> = LinkedHashMap(),
    val children: MutableList<MorphNode> = ArrayList(),
) {
    /** Bone length (evolutionism's `max(0, |offset| - 0.5)`). */
    val length: Float get() = max(0f, hypot(ox, oy) - 0.5f)

    /** Is this node bilaterally mirrored (its subtree duplicated across the mirror axis)? */
    val mirrored: Boolean get() = hypot(mirX, mirY) >= 1f

    fun deepClone(): MorphNode = MorphNode(
        name, ox, oy, scale, sym, mirX, mirY,
        LinkedHashMap(extra), children.mapTo(ArrayList()) { it.deepClone() },
    )

    /** Total nodes in this subtree (including self). */
    fun treeSize(): Int = 1 + children.sumOf { it.treeSize() }
}
