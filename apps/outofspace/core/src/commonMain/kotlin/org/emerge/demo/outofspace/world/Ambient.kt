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
) {
    val massPerTile: Long get() = perTile.total

    companion object {
        /** Nothing out there: the rim only sheds, which is every save written before this. */
        val VACUUM = Ambient(Mixture.EMPTY, Temperature.AMBIENT_KELVIN)

        /** Sea-level air on a temperate world — the same mixture a hull is pressurised with. */
        val EARTHLIKE = Ambient(Stuff.AMBIENT_AIR, Temperature.AMBIENT_KELVIN)

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
        )
    }
}
