package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a sink says it will take.
 *
 * The whole point of the type is the difference between **momentarily full** and **finally
 * satisfied**, so most of what is worth asserting here is about that distinction rather than about
 * the filtering, which is [buildableFrom]'s and is tested with it.
 */
class AcceptanceTest {

    private val railBill = conduitBillOfMaterials(Conduit.Rail)

    private fun iron(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0)

    // ── An appetite that never ends ───────────────────────────────────────────

    @Test
    fun `a machine takes anything, for ever`() {
        assertTrue(Acceptance.ANYTHING.isUnlimited)
        assertFalse(Acceptance.ANYTHING.isSatisfied, "an unlimited appetite is never done")
        assertTrue(Acceptance.ANYTHING.admits(iron(1_000L)))
        // Including things no construction site would touch. A vent will happily swallow slag.
        assertTrue(Acceptance.ANYTHING.admits(Mixture.of(Species.Quartz to 1_000L, energy = 0)))
    }

    /**
     * ⚠️ The assertion the whole design rests on. If a machine's appetite were finite, then every
     * vessel — which is to say every vessel with a tank on it — would look nearly satisfied, and
     * rationing the network by demand would ration it to a standstill.
     */
    @Test
    fun `an unlimited appetite is not a large number`() {
        assertTrue(
            Acceptance.ANYTHING.wanted == Acceptance.UNLIMITED,
            "a machine's appetite must be a different kind of number, not a big one",
        )
    }

    // ── An appetite that ends ─────────────────────────────────────────────────

    @Test
    fun `a construction site wants exactly what it is short by`() {
        val site = Acceptance.forBill(railBill, shortfall = 400L)
        assertFalse(site.isUnlimited, "a site is the one sink with a final total")
        assertFalse(site.isSatisfied)
        assertTrue(site.wanted == 400L, "it wants the shortfall, not the whole bill")
    }

    @Test
    fun `a finished site takes nothing at all`() {
        val done = Acceptance.forBill(railBill, shortfall = 0L)
        assertTrue(done.isSatisfied)
        assertFalse(
            done.admits(iron(Long.MAX_VALUE / 4)),
            "a site that is built refuses material it would otherwise have taken",
        )
    }

    /**
     * ⛔ The anti-exploit, restated through the new door. A site that let anything past would be a
     * free length of track, so what it refuses it refuses at the door rather than after the fact.
     */
    @Test
    fun `a construction site refuses what it cannot be built from`() {
        val site = Acceptance.forBill(railBill, shortfall = railBill.total)
        assertTrue(site.admits(iron(1_000L)), "a rail is iron and iron builds it")
        assertFalse(
            site.admits(Mixture.of(Species.Quartz to 1_000L, energy = 0)),
            "and a lump of quartz does not, however much of it there is",
        )
    }

    /**
     * Being short is not the same as being fussy, and the two must not collapse into each other: a
     * site with plenty still left to want refuses the wrong stuff, and a site with the right stuff
     * on offer refuses it once it is finished.
     */
    @Test
    fun `quantity and quality are separate refusals`() {
        val wrongStuff = Mixture.of(Species.Quartz to 1_000L, energy = 0)
        val rightStuff = iron(1_000L)

        val hungry = Acceptance.forBill(railBill, shortfall = railBill.total)
        val full = Acceptance.forBill(railBill, shortfall = 0L)

        assertTrue(hungry.admits(rightStuff))
        assertFalse(hungry.admits(wrongStuff), "hungry, but not indiscriminate")
        assertFalse(full.admits(rightStuff), "the right stuff, but there is nowhere left to put it")
        assertFalse(full.admits(wrongStuff))
    }
}
