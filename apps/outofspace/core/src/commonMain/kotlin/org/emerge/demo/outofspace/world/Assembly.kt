package org.emerge.demo.outofspace.world

import org.emerge.sim.core.physics.primitives.Coord

/**
 * What is bolted to what — the world's welds, as a forest.
 *
 * ### Why a forest and not a field on the vessel
 *
 * A berth used to be one nullable [Weld] on [VesselState], which encoded three separate claims in
 * one field: that there is **at most one** joint, that the **vessel** is always the thing everything
 * else hangs off, and that the vessel's own momentum fields mean *the pair's* whenever it is set.
 * The first two are false of anything the game is heading towards — two ships at one terminal, a
 * ship at two terminals, a station moored to a station, a rock under tow — and the third is a fact
 * about dynamics smuggled into a fact about geometry.
 *
 * So: a weld names **two members by id** and freezes the child's pose in the parent's frame. Nothing
 * here knows what a member *is*. That is the property worth having, because it is what stops this
 * file being rewritten when `PLAN_rigid_bodies.md` step 6 finishes and a vessel becomes a
 * [RigidBody] like everything else — a weld between two ids does not care.
 *
 * ### ⛔ The root carries the assembly's dynamics
 *
 * One member of each tree is the **root**: the one with no weld above it. It holds the assembly's
 * momentum, angular momentum and mass distribution, and every other member's pose is *derived* from
 * it by [poseOf]. That is what makes a weld rigid with no constraint to solve — see [Welding], whose
 * header explains why the pair's three numbers living on one member is the whole of the feature.
 *
 * ⚠️ **Today the root is always the vessel**, because the vessel is the only member with an
 * integrator of its own. Nothing in this file assumes it; the sim does, and [rootOf] is what it will
 * ask when that stops being true.
 */
class Assembly(
    /** Every joint in the world. Order is not significant; [descendants] imposes one. */
    val welds: List<Weld> = emptyList(),
) {

    val isEmpty: Boolean get() = welds.isEmpty()

    /** The weld holding [memberId] to something, or null when it is a root or is not welded at all. */
    fun weldTo(memberId: Int): Weld? = welds.firstOrNull { it.childId == memberId }

    /** Whether [memberId] hangs off something — i.e. its pose is dictated rather than integrated. */
    fun isHeld(memberId: Int): Boolean = weldTo(memberId) != null

    /**
     * The root of [memberId]'s tree: itself, if it hangs off nothing.
     *
     * ⚠️ Bounded by [welds] size rather than by trusting the shape, because a cycle is a hang and a
     * hang in the tick is worse than a wrong answer. Nothing can build one — [plus] refuses — and
     * this is the second lock on the same door.
     */
    fun rootOf(memberId: Int): Int {
        var at = memberId
        repeat(welds.size + 1) {
            at = weldTo(at)?.parentId ?: return at
        }
        return at
    }

    /**
     * Everything hanging off [rootId], **parents before children**, excluding the root itself.
     *
     * The order is the contract: [poseOf] and [distribution] both walk down from a member whose
     * answer is already known, so a child may never be visited before its parent.
     */
    fun descendants(rootId: Int): List<Weld> {
        if (welds.isEmpty()) return emptyList()
        val out = ArrayList<Weld>(welds.size)
        val reached = HashSet<Int>()
        reached.add(rootId)
        // A pass per level at worst, and there can be no more levels than welds.
        repeat(welds.size) {
            var grew = false
            for (weld in welds) {
                if (weld.childId in reached) continue
                if (weld.parentId !in reached) continue
                out.add(weld)
                reached.add(weld.childId)
                grew = true
            }
            if (!grew) return out
        }
        return out
    }

    /** Whether [memberId] is in the tree rooted at [rootId] — the root included. */
    fun holds(rootId: Int, memberId: Int): Boolean =
        memberId == rootId || descendants(rootId).any { it.childId == memberId }

    /**
     * Where [memberId] is in the world, given where its root has got to.
     *
     * ⛔ **The whole of "rigid", and the only place a held member's pose may come from.** Each weld
     * carries the child's centre of mass in its parent's frame, frozen at capture, so a chain of
     * them is a chain of one rotation and one translation each — no constraint, no iteration, and
     * nothing to drift. It generalises `stationPose`, which was this with the chain length nailed
     * to one.
     *
     * [aboutOf] supplies each member's own mass distribution, because a [Pose] is anchored on the
     * centre of mass it is handed and a member's is its own, not its parent's.
     */
    fun poseOf(memberId: Int, rootPose: Pose, aboutOf: (Int) -> MassDistribution): Pose {
        val weld = weldTo(memberId) ?: return rootPose
        val parent = poseOf(weld.parentId, rootPose, aboutOf)
        return Pose(
            x = parent.toWorldX(weld.childX, weld.childY),
            y = parent.toWorldY(weld.childX, weld.childY),
            ang = Coord(parent.ang.raw + weld.childAng),
            about = aboutOf(memberId),
        )
    }

    /**
     * The mass distribution of the whole tree rooted at [rootId], **in the root's frame**.
     *
     * A fold of [Composite.combined] over the members, which is exact for any number of them:
     * combining is associative in the sense that matters here, because each step states the next
     * member's offset against the accumulated centre rather than against the first member's. That
     * is why this can generalise a two-member `jointOf` without touching [Composite] at all.
     *
     * ⚠️ **Every offset is a difference, in the root's axes, in millitiles**, for the reason
     * [Composite] gives at length: `Σ m·x` over absolute coordinates leaves `Long` well inside the
     * distances this game flies.
     *
     * Returns [rootAbout] unchanged when nothing hangs off the root, and that identity is load
     * bearing: an undocked vessel must pay nothing and, more to the point, accumulate no rounding.
     */
    fun distribution(
        rootId: Int,
        rootPose: Pose,
        rootAbout: MassDistribution,
        aboutOf: (Int) -> MassDistribution?,
    ): MassDistribution {
        val held = descendants(rootId)
        if (held.isEmpty()) return rootAbout
        var acc = rootAbout
        for (weld in held) {
            val about = aboutOf(weld.childId) ?: continue
            val at = poseOf(weld.childId, rootPose, { id -> aboutOf(id) ?: MassDistribution.EMPTY })
            // The member's centre of mass, brought into the root's frame. A pose is anchored on its
            // own centre, so `at.x`/`at.y` already *are* that centre in the world.
            val comX = rootPose.toLocalX(at.x, at.y)
            val comY = rootPose.toLocalY(at.x, at.y)
            val comMilliX = comX / Rotation.PER_MILLI_TILE
            val comMilliY = comY / Rotation.PER_MILLI_TILE
            val member = MassDistribution(
                mass = about.mass,
                comMilliX = comMilliX,
                comMilliY = comMilliY,
                comX = comX,
                comY = comY,
                // ⚠️ A gyration radius is about the member's **own** centre and is unchanged by
                // where that centre is or which way it is turned; [Composite] applies the parallel
                // axis term itself. Turning it here would apply it twice.
                gyrationSq = about.gyrationSq,
            )
            acc = Composite.combined(
                acc, member,
                comMilliX - acc.comMilliX, comMilliY - acc.comMilliY,
            ).about
        }
        return acc
    }

    /**
     * This assembly with [weld] added.
     *
     * ⛔ **Refuses a second parent and refuses a cycle**, because both are states the pose walk
     * cannot answer: a member with two parents has two poses, and a cycle has none. Silent — the
     * caller is an edit, and an edit that cannot happen leaves the world alone.
     */
    fun plus(weld: Weld): Assembly {
        if (weld.childId == weld.parentId) return this
        if (isHeld(weld.childId)) return this
        // The parent must not already hang off the child, at any depth: that is the cycle.
        if (rootOf(weld.parentId) == weld.childId) return this
        return Assembly(welds + weld)
    }

    /**
     * This assembly with [memberId] cut loose from whatever holds it.
     *
     * ⚠️ **Only the one weld goes.** Anything welded to [memberId] stays welded to it and leaves as
     * its own tree, with [memberId] as the new root — which is what letting go of a terminal with a
     * second ship moored on the far side has to mean.
     */
    fun without(memberId: Int): Assembly {
        val weld = weldTo(memberId) ?: return this
        return Assembly(welds - weld)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Assembly && welds == other.welds)

    override fun hashCode(): Int = welds.hashCode()

    override fun toString(): String = "Assembly(${welds.joinToString(", ")})"

    companion object {
        /** Nothing welded to anything — what every world that has never docked has. */
        val NONE = Assembly()
    }
}

/**
 * Who the members are, in one id space.
 *
 * ⛔ **A body has no identity and a weld needs one**, which is the argument [Station.id] already
 * makes against using an index into [VesselState.bodies] — the spawner rebuilds that list every tick
 * and rocks despawning around a station shift it. So a member says who it is and a weld names it.
 *
 * One space rather than a sealed pair of ship-or-station, because the point of the exercise is that
 * a weld does not know what a member is. Stations are numbered from one; zero is spoken for.
 */
object Member {
    /** The player's vessel. Reserved, so [Station.id] starts at one. */
    const val VESSEL: Int = 0
}

/**
 * One joint: the child's pose in the parent's frame, frozen at capture and **never recomputed**.
 *
 * ⛔ That freezing is what makes the weld rigid. Recomputing the offset per tick from the two
 * members' poses would make it a description of wherever they had drifted to rather than a rule
 * about where they must be.
 *
 * The two ends are named asymmetrically because they *are* asymmetric today: a vessel presents a
 * [org.emerge.demo.outofspace.world.machine.DockingPort] on its deck and a station presents a
 * [DockNode] cut into its hull. When a vessel becomes a body both ends become the same kind of
 * thing, and only these two fields change.
 */
class Weld(
    /** The held member — the one whose pose is dictated. */
    val childId: Int,
    /** What holds it. Nearer the root, by construction: [Assembly.plus] refuses anything else. */
    val parentId: Int,
    /** The child's **centre of mass** in the parent's frame, in [Flight.PER_TILE]s. */
    val childX: Long,
    val childY: Long,
    /** How far the child is turned relative to the parent, as a [Coord] raw difference. */
    val childAng: Int,
    /** The parent's mouth: a docking port, by its centre tile. */
    val portTile: TileIndex,
    /** The child's mouth: which of its [DockNode]s. */
    val nodeIndex: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Weld && childId == other.childId && parentId == other.parentId &&
            childX == other.childX && childY == other.childY && childAng == other.childAng &&
            portTile == other.portTile && nodeIndex == other.nodeIndex)

    override fun hashCode(): Int = (childId * 31 + parentId) * 31 + portTile.index

    override fun toString(): String = "Weld($childId on $parentId, berth $nodeIndex)"
}
