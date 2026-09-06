package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget

/**
 * **What is outside the grid.**
 *
 * For the whole of this game's life that has been hard vacuum, stated by absence: `beyond()` read a
 * potential of zero and the rim only ever subtracted. A vessel near a planet is in something, and
 * the something is here.
 *
 * ⛔ **This is the only place a planet exists**, and deliberately so. There is no world map, no
 * altitude and no sphere — a vessel is *in* an atmosphere or it is not, and what that means is
 * entirely "what does a tile of the stuff outside weigh, and how hot is it". Everything a player
 * would call flying through air comes out of the boundary exchange at the rim from those two
 * numbers; nothing else in the sim needs to learn what a planet is.
 *
 * ⚠️ **At rest in the world**, and that is what makes drag work rather than an assumption to revisit.
 * A ship moving through it scoops gas that is not moving, the coupling then drags that gas up to the
 * hull's speed at the ship's expense, and *that is the drag* — see [org.emerge.demo.outofspace.world.airCoupling].
 * No drag law is stated anywhere. A wind would be this same class with a velocity on it.
 */
class Ambient(
    /** What one tile's volume of it holds — its density, since every tile is the same size. */
    val perTile: Mixture,
    val kelvin: Int,
    /**
     * **How bright it is out there**, in permille of full sun at one astronomical unit.
     *
     * ⛔ **One number, and deliberately only one** — see this class's doc. There is no sun direction,
     * no shadow, no day and no night, because any of those is the world map this class exists to
     * avoid. *The sun is anywhere outside the vessel* (Stu, 2026-09-06), so what a panel collects
     * depends on how much of it faces out and on nothing else.
     *
     * ⚠️ **Independent of [perTile], unlike everything else here.** Hard vacuum is the *brightest*
     * place a vessel can be, not the dimmest; density and light are two facts about a location that
     * happen to share a class.
     */
    val insolation: Int = FULL_SUN,
) {
    val massPerTile: Long get() = perTile.total

    companion object {
        /** The reference [insolation]: full sun at one AU, which is what open space near a star is. */
        const val FULL_SUN = 1_000

        /** Nothing out there: the rim only sheds, which is every save written before this. */
        val VACUUM = Ambient(Mixture.EMPTY, Temperature.AMBIENT_KELVIN)

        /**
         * Sea-level air on a temperate world — the same mixture a hull is pressurised with.
         *
         * ⚠️ Dimmer than vacuum, because an atmosphere is in the way: about three quarters of the
         * light above it reaches the ground on a clear day.
         */
        val EARTHLIKE = Ambient(Stuff.AMBIENT_AIR, Temperature.AMBIENT_KELVIN, insolation = 750)

        /**
         * The upper cloud deck of a gas giant: hydrogen and helium, cold, and **twenty times** as
         * dense as sea-level air.
         *
         * The density is the number that matters and the composition mostly is not — drag is
         * momentum carried in and out, so what a gram is made of does not change what a gram does.
         * It is stated as a real mixture anyway because the *thermal* behaviour does differ, and a
         * hull full of hydrogen is a different proposition from one full of nitrogen.
         */
        val GAS_GIANT = Ambient(
            Mixture.of(
                Species.Hydrogen to 17_000L * Budget.GRAM,
                Species.Helium to 3_000L * Budget.GRAM,
                energy = 0,
            ),
            kelvin = 165,
            // Jupiter is 5.2 AU out, and light falls off as the square: 1/27th of what Earth gets.
            insolation = 37,
        )
    }
}
