package org.emerge.demo.outofspace.world

/**
 * What counts as *nothing there* for the purposes of showing it to a player.
 *
 * Diffusion spreads a trace of gas into every tile it can reach, and integers mean that trace never
 * quite reaches zero. Every readout that tested `> 0` therefore reported it: a vacuum tile tinted as
 * air because it held a gram, a "0% atm" pressure row, a composition line reading `OXYG 0%`, and —
 * loudest of all — a full-length flow arrow, because [FlowField.speedAt] is mass moved over mass
 * held, so half a gram leaving a one-gram tile is half a tile per tick.
 *
 * None of that is wrong; all of it is noise, and it drowns the quantities that matter. So the
 * readouts agree on one floor and say nothing below it.
 *
 * ⚠️ **Presentation only.** Nothing in the sim, and nothing in a ledger, may read these: the mass and
 * energy balances are exact by construction and a "close enough to zero" in one of them would be a
 * leak nobody sees. The HUD's balance rows deliberately keep testing against zero.
 */
object Negligible {

    /**
     * The floor, as thousandths of a tile of ordinary air.
     *
     * Five, because the readouts print pressure and density as whole percentages of an atmosphere,
     * so anything under this rounds to `0%` anyway — the rule is "if the number would print as
     * nothing, don't print the row", which needs no separate justification and leaves no band where
     * a tile is drawn as full but reads as empty.
     */
    const val PER_MILLE: Long = 5L

    /** Gas mass in a tile below which the tile reads as empty. */
    val MASS: Long = AMBIENT_TILE_MASS * PER_MILLE / 1000L

    /** Pressure (millimoles) below which the tile reads as vacuum. */
    val MILLIMOLES: Long = AMBIENT_PRESSURE * PER_MILLE / 1000L

    /**
     * Net mass per tick across a tile below which the flow reads as still.
     *
     * Held to the same fraction as everything else rather than a smaller one: a flow worth drawing an
     * arrow for is a flow that would move a visible share of a tile's air within a few hundred ticks.
     */
    val FLUX_MASS: Long = MASS

    /** Is there so little gas here that the tile should read as empty? */
    fun gas(mass: Long): Boolean = mass < MASS

    /** Is the pressure here low enough that the tile should read as vacuum? */
    fun pressure(millimoles: Long): Boolean = millimoles < MILLIMOLES

    /**
     * Is this tile's flow beneath notice — either because the flux itself is a trickle, or because
     * the gas being moved is a trace? Both are needed: the speed is a ratio, so a trace tile can
     * report any speed at all.
     */
    fun flow(fluxX: Long, fluxY: Long, tileMass: Long): Boolean {
        if (gas(tileMass)) return true
        val ax = if (fluxX < 0L) -fluxX else fluxX
        val ay = if (fluxY < 0L) -fluxY else fluxY
        return ax + ay < FLUX_MASS
    }

    /**
     * Is this species too small a share of its mixture to name? Below one percent it prints as `0%`,
     * which is a line of text saying nothing.
     */
    fun share(part: Long, total: Long): Boolean = total <= 0L || part * 100L < total
}
