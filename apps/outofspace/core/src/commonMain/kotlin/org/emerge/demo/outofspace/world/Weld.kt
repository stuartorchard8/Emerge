package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * Bolting the vessel to a station, and letting go again — `PLAN_economy.md` §7.
 *
 * ### The vessel carries the pair
 *
 * A weld could be represented from either end. This one puts the pair's **momentum, angular momentum
 * and mass distribution on the vessel**, and derives the station's pose from the vessel's. That
 * choice is what makes the whole feature small: the reducer already advances the vessel's pose by its
 * own momentum about its own centre of mass, so handing it the *pair's* three numbers turns that
 * same expression into the pair's advance, with no second integrator and no constraint to solve.
 *
 * ⛔ **Nothing is minted.** Capture is an inelastic collision: total linear momentum and total angular
 * momentum about the joint centre are both conserved, and the half that leaves the station is booked
 * through [VesselState.bodyImpulseX] and [VesselState.bodyAngImpulse] — the stores that exist to name
 * exactly this exchange. Release divides the pair's motion back out the same way.
 */
object Weld {

    /**
     * Where the station sits in the vessel's frame, and the pair's motion, at the moment of capture.
     *
     * The offset is frozen here and never recomputed — see [DockLink]. Returns the link, and the
     * vessel's new momentum, angular momentum and the transfers to book.
     */
    fun capture(
        shipPose: Pose,
        shipAbout: MassDistribution,
        shipImpulseX: Long,
        shipImpulseY: Long,
        shipAngImpulse: Long,
        station: RigidBody,
        portTile: TileIndex,
        nodeIndex: Int,
    ): Capture {
        val economy = station.station ?: error("cannot dock to a body with no economy")
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
            link = DockLink(
                stationId = economy.id,
                portTile = portTile,
                nodeIndex = nodeIndex,
                stationLocalX = shipPose.toLocalX(station.positionX, station.positionY),
                stationLocalY = shipPose.toLocalY(station.positionX, station.positionY),
                stationRelativeAng = station.ang.raw - shipPose.ang.raw,
            ),
            gainedImpulseX = gainedX,
            gainedImpulseY = gainedY,
            gainedAngImpulse = gainedAng,
        )
    }

    /** What capture did: the link, and what the vessel took off the station. */
    class Capture(
        val link: DockLink,
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
        // The station's centre of mass, in the vessel's frame, in millitiles.
        val comX = shipPose.toLocalX(station.comX, station.comY) / Composite.PER_MILLI_TILE
        val comY = shipPose.toLocalY(station.comX, station.comY) / Composite.PER_MILLI_TILE
        val stationAbout = MassDistribution(
            mass = station.mass,
            comMilliX = comX,
            comMilliY = comY,
            gyrationSq = station.about.gyrationSq,
        )
        val joined = Composite.combined(shipAbout, stationAbout, comX - shipAbout.comMilliX, comY - shipAbout.comMilliY)
        return Joint(joined.about, comX, comY)
    }

    /** The pair's distribution in the vessel's frame, plus the station's own centre within it. */
    class Joint(val about: MassDistribution, val stationComX: Long, val stationComY: Long)

    /** Where the station must be, given where the vessel has got to. The whole of "rigid". */
    fun stationPose(shipPose: Pose, link: DockLink): Pose = Pose(
        x = shipPose.toWorldX(link.stationLocalX, link.stationLocalY),
        y = shipPose.toWorldY(link.stationLocalX, link.stationLocalY),
        ang = Coord(shipPose.ang.raw + link.stationRelativeAng),
    )

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
