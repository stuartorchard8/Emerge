package org.emerge.demo.norns.morph

import org.emerge.demo.norns.gene.GeneRng
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Morphology-genome operations, ported to 2D from Stu's `evolutionism` prototype: **mutation**
 * (perturb params + structural add/insert/remove), **crossbreed** (name-keyed align → average matched
 * → inherit unmatched, with reparent/deparent compensation so a part inserted in one lineage still
 * aligns), and a scalar **genetic distance** for "how related / can these breed". Deterministic given
 * a [GeneRng]. Pure data (no rendering, no integration with the brain/biochem genome yet).
 *
 * Frame convention: a node's children live in a frame whose +Y ("forward") points along the node's
 * own offset direction; [reparent]/[deparent] rotate by that direction + (un)compound scale, which is
 * what keeps geometry stable when the tree's topology differs between two parents.
 */
object MorphGenome {

    private const val DEG = 180f / 3.1415927f

    // ---- 2D geometry helpers ----

    /** Rotate (x,y) CCW by [deg]. */
    fun rotate(x: Float, y: Float, deg: Float): Pair<Float, Float> {
        val r = deg / DEG; val c = cos(r); val s = sin(r)
        return (x * c - y * s) to (x * s + y * c)
    }

    /** The rotation (deg) that maps forward (+Y) onto the direction (ox,oy). */
    private fun dirAngle(ox: Float, oy: Float): Float = kotlin.math.atan2(-ox, oy) * DEG

    /** Angle between two 2D vectors in degrees (0 if either is ~zero). */
    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-6f || lb < 1e-6f) return 0f
        return acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)) * DEG
    }

    /** Bake [parent]'s transform into [child] (child keeps world placement when parent is removed /
     *  collapsed): rotate the child's offset by the parent's direction, compound scale. */
    fun deparent(child: MorphNode, parent: MorphNode) {
        val (x, y) = rotate(child.ox, child.oy, dirAngle(parent.ox, parent.oy))
        child.ox = x; child.oy = y; child.scale *= parent.scale
    }

    /** Inverse of [deparent]: re-express [child] in [parent]'s local frame. */
    fun reparent(child: MorphNode, parent: MorphNode) {
        val (x, y) = rotate(child.ox, child.oy, -dirAngle(parent.ox, parent.oy))
        child.ox = x; child.oy = y; child.scale = if (parent.scale != 0f) child.scale / parent.scale else child.scale
    }

    // ---- mutation ----

    /** Mutate [root] in place: perturb every node's params, and occasionally apply a structural
     *  operator (grow a limb / splice a segment / drop a node). [intensity] scales perturbation size,
     *  [structuralOdds] the per-node chance of a structural change. */
    fun mutate(root: MorphNode, rng: GeneRng, intensity: Float = 0.2f, structuralOdds: Float = 0.05f) {
        perturb(root, rng, intensity)
        mutateChildren(root, rng, intensity, structuralOdds)
    }

    private fun mutateChildren(parent: MorphNode, rng: GeneRng, intensity: Float, odds: Float) {
        if (rng.nextFloat() < odds * intensity) addLimb(parent, rng)            // grow a new leaf
        for (child in parent.children.toList()) {
            val roll = rng.nextFloat()
            when {
                roll < odds * intensity -> { removeNode(parent, child); continue }       // drop, splice children up
                roll < 2 * odds * intensity -> insertSegment(parent, child, rng)         // splice a segment above
            }
            perturb(child, rng, intensity)
            mutateChildren(child, rng, intensity, odds)
        }
    }

    private fun perturb(n: MorphNode, rng: GeneRng, intensity: Float) {
        val (rx, ry) = rotate(n.ox, n.oy, rng.signed() * intensity * 30f)       // rotate offset ±30°·intensity
        val lenScale = 1f + rng.signed() * intensity * 0.5f
        n.ox = rx * lenScale; n.oy = ry * lenScale
        n.scale = (n.scale * (1f + rng.signed() * intensity)).coerceAtLeast(0.01f)
        n.sym = (n.sym * (1f + rng.signed() * intensity)).roundToInt().coerceAtLeast(0)
        if (n.mirX == 0f && n.mirY == 0f) {
            if (rng.nextFloat() < 0.01f) { val (mx, my) = rotate(0.01f, 0f, rng.nextFloat() * 360f); n.mirX = mx; n.mirY = my }
        } else {
            val (mx, my) = rotate(n.mirX, n.mirY, rng.signed() * intensity * 30f)
            val ms = 1f + rng.signed() * intensity
            n.mirX = mx * ms; n.mirY = my * ms
        }
    }

    /** Grow a new leaf off [parent], pointing further out by the parent's bone length. */
    private fun addLimb(parent: MorphNode, rng: GeneRng) {
        parent.children.add(MorphNode(genName(rng), ox = 0f, oy = hypot(parent.ox, parent.oy)))
    }

    /** Splice a fresh node between [child] and [parent], inheriting the child's offset+scale; the
     *  child resets to point forward at its old length (the new parent now carries the placement). */
    private fun insertSegment(parent: MorphNode, child: MorphNode, rng: GeneRng) {
        val i = parent.children.indexOf(child); if (i < 0) return
        val mag = hypot(child.ox, child.oy)
        val seg = MorphNode(genName(rng), ox = child.ox, oy = child.oy, scale = child.scale)
        child.ox = 0f; child.oy = mag; child.scale = 1f
        seg.children.add(child)
        parent.children[i] = seg
    }

    /** Remove [child], baking its transform into its own children and splicing them into [parent]. */
    private fun removeNode(parent: MorphNode, child: MorphNode) {
        val i = parent.children.indexOf(child); if (i < 0) return
        val freed = child.children.map { gc ->
            deparent(gc, child)
            gc.sym += child.sym; gc.mirX += child.mirX; gc.mirY += child.mirY
            gc
        }
        parent.children.removeAt(i)
        parent.children.addAll(i, freed)
    }

    // ---- crossbreed ----

    /** Blend two genomes into a child: align children by name, average matched params, inherit
     *  unmatched parts, tolerating a topology shift of one level (a segment inserted in one parent). */
    fun crossbreed(a: MorphNode, b: MorphNode): MorphNode {
        val node = mergeAttributes(a, b)
        val matched0 = BooleanArray(a.children.size)
        val matched1 = BooleanArray(b.children.size)

        // parse 1 — exact name match
        for (i0 in a.children.indices) {
            if (matched0[i0]) continue
            for (i1 in b.children.indices) {
                if (matched1[i1]) continue
                if (a.children[i0].name == b.children[i1].name) {
                    node.children.add(crossbreed(a.children[i0], b.children[i1]))
                    matched0[i0] = true; matched1[i1] = true; break
                }
            }
        }
        // parse 2 — a inserted an intermediate: a.child's grandchildren match b's children; keep the intermediate
        for (i0 in a.children.indices) {
            if (matched0[i0]) continue
            val c0 = a.children[i0]; var rebuilt: MorphNode? = null
            for (gc0 in c0.children) {
                for (i1 in b.children.indices) {
                    if (matched1[i1] || gc0.name != b.children[i1].name) continue
                    val c1 = b.children[i1].deepClone(); reparent(c1, c0)
                    if (rebuilt == null) { rebuilt = shallowClone(c0); node.children.add(rebuilt) }
                    rebuilt.children.add(crossbreed(gc0, c1))
                    matched0[i0] = true; matched1[i1] = true; break
                }
            }
        }
        // parse 3 — b inserted an intermediate: b.child's grandchildren match a's children; drop the intermediate
        for (i1 in b.children.indices) {
            if (matched1[i1]) continue
            val c1 = b.children[i1]
            for (gc1raw in c1.children) {
                for (i0 in a.children.indices) {
                    if (matched0[i0] || gc1raw.name != a.children[i0].name) continue
                    val gc1 = gc1raw.deepClone(); deparent(gc1, c1)
                    node.children.add(crossbreed(gc1, a.children[i0]))
                    matched1[i1] = true; matched0[i0] = true; break
                }
            }
        }
        // tack on whatever stayed unmatched
        for (i0 in a.children.indices) if (!matched0[i0]) node.children.add(a.children[i0].deepClone())
        for (i1 in b.children.indices) if (!matched1[i1]) node.children.add(b.children[i1].deepClone())
        return node
    }

    private fun shallowClone(n: MorphNode) = MorphNode(n.name, n.ox, n.oy, n.scale, n.sym, n.mirX, n.mirY, LinkedHashMap(n.extra))

    private fun mergeAttributes(a: MorphNode, b: MorphNode): MorphNode {
        val extra = LinkedHashMap<String, Float>(a.extra)
        for ((k, v) in b.extra) extra[k] = if (k in extra) (extra[k]!! + v) / 2f else v
        return MorphNode(
            a.name,
            (a.ox + b.ox) / 2f, (a.oy + b.oy) / 2f,
            (a.scale + b.scale) / 2f,
            ((a.sym + b.sym) / 2f).roundToInt(),
            (a.mirX + b.mirX) / 2f, (a.mirY + b.mirY) / 2f,
            extra,
        )
    }

    // ---- genetic distance (speciation / breeding eligibility) ----

    /** Scalar genetic distance between two genomes (0 = identical). Higher = less related; the caller
     *  can gate breeding on a threshold (evolutionism used < 16). Symmetric. */
    fun distance(a: MorphNode, b: MorphNode): Float {
        var d = attributeDifferences(a, b)
        val matched0 = BooleanArray(a.children.size)
        val matched1 = BooleanArray(b.children.size)
        for (i0 in a.children.indices) {
            if (matched0[i0]) continue
            for (i1 in b.children.indices) {
                if (matched1[i1]) continue
                if (a.children[i0].name == b.children[i1].name) {
                    d += distance(a.children[i0], b.children[i1]); matched0[i0] = true; matched1[i1] = true; break
                }
            }
        }
        for (i0 in a.children.indices) {
            if (matched0[i0]) continue
            for (gc0 in a.children[i0].children) {
                for (i1 in b.children.indices) {
                    if (matched1[i1] || gc0.name != b.children[i1].name) continue
                    d += distance(gc0, b.children[i1]) + 4f                    // hierarchy was off by a level
                    matched0[i0] = true; matched1[i1] = true; break
                }
            }
        }
        for (i1 in b.children.indices) {
            if (matched1[i1]) continue
            for (gc1 in b.children[i1].children) {
                for (i0 in a.children.indices) {
                    if (matched0[i0] || gc1.name != a.children[i0].name) continue
                    d += distance(gc1, a.children[i0]) + 4f
                    matched1[i1] = true; matched0[i0] = true; break
                }
            }
        }
        for (i0 in a.children.indices) if (!matched0[i0]) d += a.children[i0].treeSize() * 4f
        for (i1 in b.children.indices) if (!matched1[i1]) d += b.children[i1].treeSize() * 4f
        return d
    }

    private fun attributeDifferences(a: MorphNode, b: MorphNode): Float {
        var d = 0f
        val sqA = a.ox * a.ox + a.oy * a.oy; val sqB = b.ox * b.ox + b.oy * b.oy
        var ratio = if (sqA < 1e-9f || sqB < 1e-9f) 1f else sqA / sqB
        if (ratio < 1f) ratio = 1f / ratio
        d += ratio - 1f                                                        // 2× length = 1 diff
        val sa = a.scale; val sb = b.scale
        d += (if (sa > sb) (if (sb != 0f) sa / sb else 1f) else (if (sa != 0f) sb / sa else 1f)) - 1f
        d += angleBetween(a.ox, a.oy, b.ox, b.oy) / 30f                        // 30° = 1 diff
        d += angleBetween(a.mirX, a.mirY, b.mirX, b.mirY) / 30f
        d += abs(a.sym - b.sym).toFloat()                                      // 1 symmetry axis = 1 diff
        return d
    }

    private fun genName(rng: GeneRng): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_"
        return buildString { repeat(8) { append(chars[(rng.nextFloat() * chars.length).toInt().coerceIn(0, chars.length - 1)]) } }
    }
}
