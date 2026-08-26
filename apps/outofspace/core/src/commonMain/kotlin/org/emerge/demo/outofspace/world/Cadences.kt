package org.emerge.demo.outofspace.world

/**
 * When each pass that a view has to interpolate last ran — see [Cadence].
 *
 * **Presentation only**, on the same terms as [Motion]: nothing in the sim reads it, nothing
 * branches on it, and the save does not carry it. A loaded world simply shows everything settled
 * for one pass and then animates again.
 *
 * The rail's own stamp is not here, because it has somewhere better to be: the rail pass already
 * writes a [Motion], and a record and the time it was written belong together. What is left are the
 * passes whose product is a *field* — a temperature, a pressure — with no record object of its own
 * to hang a stamp on. Those go here rather than growing a field on `VesselState` each, which is the
 * only reason this type exists.
 *
 * ⚠️ **These are two numbers and no arrays, which is what lets them ride a resize.** Everything
 * derived from the grid has to be re-derived when the grid grows (see `VesselState.resized`); a
 * cadence is a tick and a span, so it means the same thing whatever shape the world is. The
 * *snapshots* a view fades between are grid-sized and are the view's problem, not this one's.
 */
data class Cadences(
    /** The solid-heat pass — conduction, radiation, and what the overlay is a picture of. */
    val heat: Cadence = Cadence.SETTLED,
    /**
     * The fluid pass — diffusion, and so the whole of what the air, pressure and density overlays
     * are pictures of.
     *
     * ⚠️ **The pressure overlay keys off this one, not off the pressure pass**, which is not the
     * confusion it looks like. `Stuff.pressureAt` is millimoles of gas in the tile, derived from the
     * same masses everything else reads; the pressure pass computes the *forces* that field exerts
     * and moves no gas at all. What the overlay draws is moved by diffusion, so this is the pass
     * whose span it fades across. A stamp for the pressure pass would be a stamp nothing reads.
     */
    val fluid: Cadence = Cadence.SETTLED,
)
