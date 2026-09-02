package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * Bolting the vessel to a station, and letting go again — `PLAN_economy.md` §7.
 *
 * ### The root carries the assembly
 *
 * A weld could be represented from either end. This one puts the assembly's **momentum, angular
 * momentum and mass distribution on its root**, and derives every held member's pose from it — see
 * [Assembly.poseOf]. That choice is what makes the whole feature small: the reducer already advances
 * the vessel's pose by its own momentum about its own centre of mass, so handing it the *assembly's*
 * three numbers turns that same expression into the assembly's advance, with no second integrator
 * and no constraint to solve.
 *
 * ⚠️ **The root is the vessel today, and that is the sim's assumption rather than this file's.** The
 * functions here take a root pose and the distribution of what is welded to it; none of them asks
 * what kind of thing the root is. What is genuinely still vessel-shaped is [capture] and [release]
 * naming a `station` — the member joining or leaving — which is the last place a member's kind is
 * visible and the thing `PLAN_rigid_bodies.md` step 6 retires.
 *
 * ⛔ **Nothing is minted.** Capture is an inelastic collision: total linear momentum and total angular
 * momentum about the joint centre are both conserved, and the half that leaves the station is booked
 * through [VesselState.bodyImpulseX] and [VesselState.bodyAngImpulse] — the stores that exist to name
 * exactly this exchange. Release divides the pair's motion back out the same way.
 */
object Welding {

    /**
     * Where the joining member sits in the root's frame, and the assembly's motion, at capture.
     *
     * The offset is frozen here and never recomputed — see [Weld]. Returns the joint, and the
     * root's new momentum, angular momentum and the transfers to book.
     */
    fun capture(
        rootPose: Pose,
        /**
         * The distribution of everything already welded together, in the root's frame — **not the
         * vessel's own**. Adding a second member to an assembly is the same arithmetic as forming
         * the first pair, provided this is what the first operand is.
         */
        heldAbout: MassDistribution,
        shipImpulseX: Long,
        shipImpulseY: Long,
        shipAngImpulse: Long,
        station: RigidBody,
        portTile: TileIndex,
        nodeIndex: Int,
    ): Capture {
        val economy = station.station ?: error("cannot dock to a body with no economy")
        val shipPose = rootPose
        val shipAbout = heldAbout
        val joint = jointOf(shipPose, shipAbout, station)

        // Linear: the pair simply carries the sum. What the station had is what the vessel gains, so
        // that is the number the ledger hears about.
        val gainedX = station.impulseX
        val gainedY = station.impulseY

        // Angular about the joint centre: each member's own spin, plus each member's momentum about
        // the joint centre. `r × p`, and `r` is the arm from the joint centre to that member's own
        // centre of mass — in the vessel's axes, where the joint centre already is.
        val shipArmX = shipAbout.comMilliX - joint.about.comMilliX
        val shipArmY = shipAbout.comMilliY - joint.about.comMilliY
        val stationArmX = joint.stationComX - joint.about.comMilliX
        val stationArmY = joint.stationComY - joint.about.comMilliY

        // ⚠️ The vessel's momentum is a WORLD quantity and the arms are in the vessel's frame, so one
        // of the two has to be turned before they can be crossed. The momenta come into the frame,
        // because there are two of them and four arms.
        val shipLocalPx = shipPose.unturnedX(shipImpulseX, shipImpulseY)
        val shipLocalPy = shipPose.unturnedY(shipImpulseX, shipImpulseY)
        val stationLocalPx = shipPose.unturnedX(station.impulseX, station.impulseY)
        val stationLocalPy = shipPose.unturnedY(station.impulseX, station.impulseY)

        val orbital = cross(shipArmX, shipArmY, shipLocalPx, shipLocalPy) +
            cross(stationArmX, stationArmY, stationLocalPx, stationLocalPy)
        val gainedAng = station.angImpulse + orbital

        return Capture(
            weld = Weld(
                childId = economy.id,
                parentId = Member.VESSEL,
                childX = shipPose.toLocalX(station.positionX, station.positionY),
                childY = shipPose.toLocalY(station.positionX, station.positionY),
                childAng = station.ang.raw - shipPose.ang.raw,
                portTile = portTile,
                nodeIndex = nodeIndex,
            ),
            gainedImpulseX = gainedX,
            gainedImpulseY = gainedY,
            gainedAngImpulse = gainedAng,
        )
    }

    /**
     * Letting go: what the station takes away with it, so that **both members keep the spin the pair
     * had**.
     *
     * ⛔ **Not half the momentum each, and not the momentum each arrived with.** A pair turning at ω
     * is turning at ω all through, so the honest split is the one that leaves ω on both sides of the
     * joint — which means each member keeps its **share of the pair's inertia**, `I = m·k²`, and the
     * two shares are as unequal as the two bodies are. Halving would spin a tug off a terminal like a
     * firework; restoring what each had at capture would put a berth of arbitrary length into the
     * ledger, since the pair has been thrusting and turning ever since.
     *
     * ⛔ **A member's linear momentum is its share of the pair's, plus its own orbit.** The joint
     * centre is somewhere between the two, so a turning pair is carrying each member *around* that
     * point at `ω × r`, and that velocity is real: it is what the station is doing at the instant the
     * clamps open. Leaving it out would stop a berth dead at the far end of a turning ship, which is
     * the one release the player actually notices.
     *
     * Together those two conserve both totals exactly — the vessel keeps whatever this does not take
     * — which is [capture] run backwards, as the class note promises. See [Release] for why the two
     * angular numbers it hands back are different numbers.
     */
    fun release(
        rootPose: Pose,
        /**
         * The distribution of everything that **stays** welded, in the root's frame. With one member
         * leaving a two-member assembly that is the vessel alone; with three it is the other two,
         * and the arithmetic below does not know the difference.
         */
        remainderAbout: MassDistribution,
        pairImpulseX: Long,
        pairImpulseY: Long,
        pairAngImpulse: Long,
        station: RigidBody,
    ): Release {
        val shipPose = rootPose
        val shipAbout = remainderAbout
        val joint = jointOf(shipPose, shipAbout, station)
        val pair = joint.about
        val stationAbout = station.about

        // The two spins, each about its own centre. Their sum is *less* than the pair's angular
        // momentum, and by exactly the orbital term below — the pair's `k²` carries a parallel-axis
        // distance for each member that neither member's own does.
        val keptSpin = inertiaShare(pairAngImpulse, pair, shipAbout)
        val stationSpin = inertiaShare(pairAngImpulse, pair, stationAbout)

        // The station's orbit about the joint centre: `ω × r`, in the vessel's axes, where the arm
        // already is. `ω × r = (−ω·r_y, ω·r_x)`, and [spinSpeed] answers the magnitude of each.
        val spin = angularVelocity(pairAngImpulse, pair)
        val armX = (joint.stationComX - pair.comMilliX) * Rotation.PER_MILLI_TILE
        val armY = (joint.stationComY - pair.comMilliY) * Rotation.PER_MILLI_TILE
        // ⚠️ Momentum, not velocity, before it is turned: `p = m·v / PER_TILE` is the inverse of
        // [VesselState.velocityXAt], and doing it here keeps the whole exchange in one unit.
        val orbitPx = scaledRatio(-spinSpeed(spin, armY), Flight.PER_TILE, stationAbout.mass)
        val orbitPy = scaledRatio(spinSpeed(spin, armX), Flight.PER_TILE, stationAbout.mass)

        // ⚠️ **Into the world on the way out**, because a body's momentum is a world quantity and
        // the arm that produced this one is not — the same frame change [capture] makes in the other
        // direction, and the same trap if it is skipped.
        val impulseX = scaledRatio(pairImpulseX, pair.mass, stationAbout.mass) +
            shipPose.turnedX(orbitPx, orbitPy)
        val impulseY = scaledRatio(pairImpulseY, pair.mass, stationAbout.mass) +
            shipPose.turnedY(orbitPx, orbitPy)

        return Release(
            impulseX = impulseX,
            impulseY = impulseY,
            spinAngImpulse = stationSpin,
            // The remainder, so the vessel is left holding exactly `keptSpin` however the two shares
            // rounded. Everything that is not the ship's own spin has left the ship's book.
            handedAngImpulse = pairAngImpulse - keptSpin,
        )
    }

    /**
     * What release hands over: the station's motion, and the vessel's side of the same exchange.
     *
     * ⚠️ **[spinAngImpulse] and [handedAngImpulse] are different numbers and both are right.** The
     * first is what the station is now spinning at, about its own centre. The second is everything
     * that left the vessel's angular book — the station's spin *plus* the orbital term that both
     * members' linear momenta now carry between them, which is a quantity no body holds and only the
     * ledger names. [capture] booked the same asymmetry the other way round.
     */
    class Release(
        val impulseX: Long,
        val impulseY: Long,
        val spinAngImpulse: Long,
        val handedAngImpulse: Long,
    )

    /**
     * A member's share of the pair's angular momentum: its share of the pair's inertia, `m·k²`.
     *
     * The whole of "both keep the same ω", since `L = I·ω` and ω is common. Divided by the gyration
     * radius first and the mass second, which is [angularVelocity]'s order and for its reason — the
     * moment of inertia is never materialised, here or anywhere.
     */
    private fun inertiaShare(angImpulse: Long, pair: MassDistribution, member: MassDistribution): Long {
        if (angImpulse == 0L || pair.mass <= 0L || pair.gyrationSq <= 0L) return 0L
        return scaledRatio(scaledRatio(angImpulse, pair.gyrationSq, member.gyrationSq), pair.mass, member.mass)
    }

    /** What capture did: the joint, and what the root took off the member it swallowed. */
    class Capture(
        val weld: Weld,
        val gainedImpulseX: Long,
        val gainedImpulseY: Long,
        val gainedAngImpulse: Long,
    )

    /**
     * The pair's mass distribution in the **vessel's** frame, and where the station's own centre of
     * mass sits in it.
     *
     * ⚠️ Everything is a difference and everything is in the vessel's axes. The station's centre is
     * brought into the frame through [Pose.toLocalX], so the two centres can be combined without any
     * absolute world coordinate ever being multiplied by anything — see [Composite].
     */
    fun jointOf(shipPose: Pose, shipAbout: MassDistribution, station: RigidBody): Joint {
        // The station's centre of mass, in the vessel's frame. [Pose.toLocalX] answers at the
        // position scale, so the millitile arms below are a reduction of this rather than the other
        // way about — the frame conversion's precision is kept and then spent, not discarded first.
        val comX = shipPose.toLocalX(station.comX, station.comY)
        val comY = shipPose.toLocalY(station.comX, station.comY)
        val comMilliX = comX / Rotation.PER_MILLI_TILE
        val comMilliY = comY / Rotation.PER_MILLI_TILE
        val stationAbout = MassDistribution(
            mass = station.mass,
            comMilliX = comMilliX,
            comMilliY = comMilliY,
            comX = comX,
            comY = comY,
            gyrationSq = station.about.gyrationSq,
        )
        val joined = Composite.combined(
            shipAbout, stationAbout,
            comMilliX - shipAbout.comMilliX, comMilliY - shipAbout.comMilliY,
        )
        return Joint(joined.about, comMilliX, comMilliY)
    }

    /** The pair's distribution in the vessel's frame, plus the station's own centre within it. */
    class Joint(val about: MassDistribution, val stationComX: Long, val stationComY: Long)

    /**
     * One tick of flight for whichever body is actually moving — the vessel alone, or the pair.
     *
     * ⛔ **A pair turns about the pair's centre, and that is the only thing docking changes.** So the
     * grid is re-hung on [moving]'s centre, turned and translated there, and re-hung on the vessel's
     * own centre on the way out. Undocked the two distributions are the same one and both re-hangs
     * are *exactly* the identity — [Pose.about] to the centre a pose already has rotates zero, and
     * [rotScale] returns zero for zero — so the common case pays nothing and, more to the point,
     * accumulates no rounding.
     */
    fun advance(
        pose: Pose,
        own: MassDistribution,
        moving: MassDistribution,
        by: Coord,
        dx: Long,
        dy: Long,
    ): Pose = pose.about(moving).turned(by).movedBy(dx, dy).about(own)

    /**
     * `r × p` with the arm in millitiles and the momentum in mass·tiles/tick.
     *
     * ⚠️ Through [scaledRatio] rather than as a plain product: an arm of two hundred tiles is 2e5
     * millitiles and a heavy vessel's momentum is 1e14, so the product is 2e19 and `Long` stops at
     * 9.2e18. Reducing the fraction first removes the exponent instead of raising the bound.
     */
    private fun cross(armX: Long, armY: Long, px: Long, py: Long): Long =
        signedScaled(armX, py) - signedScaled(armY, px)

    /** [scaledRatio] refuses a negative scale, so the sign is carried outside it. */
    private fun signedScaled(arm: Long, p: Long): Long {
        val magnitude = scaledRatio(if (arm < 0) -arm else arm, Rotation.MILLI_TILE, if (p < 0) -p else p)
        val negative = (arm < 0) != (p < 0)
        return if (negative) -magnitude else magnitude
    }
}
