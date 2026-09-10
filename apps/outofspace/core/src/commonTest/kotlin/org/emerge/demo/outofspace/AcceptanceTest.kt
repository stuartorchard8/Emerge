package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * What a sink says it will take.
 *
 * The whole point of the type is the difference between **momentarily full** and **finally
 * satisfied**, so most of what is worth asserting here is about that distinction rather than about
 * the filtering, which is [buildableFrom]'s and is tested with it.
 */
class AcceptanceTest {

    private val railBill = conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail))

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

    /**
     * A store: unfussy and finite, which is the corner of the grid nothing occupied before.
     *
     * ⛔ **Both questions have to be answered separately here or the fast path is wrong.**
     * `Whitelist.of` marks a tile "welcome anywhere" on `takesAnything && isUnlimited`; a store
     * satisfies the first and not the second, so it must be carried as an ordinary metered route.
     * Reading either flag alone gets one of the two cases wrong — an endless dump, or a tank the
     * network refuses to send anything to at all.
     */
    @Test
    fun `a store takes anything and still runs out`() {
        val store = Acceptance.upTo(500L)
        assertTrue(store.takesAnything, "a store refuses nothing while it has room")
        assertFalse(store.isUnlimited, "but its room is a quantity, not a kind of number")
        assertFalse(store.isSatisfied)
        assertTrue(store.admits(iron(1_000L)), "kind is not the question a store asks")
        assertTrue(
            store.admits(Mixture.of(Species.Quartz to 1_000L, energy = 0)),
            "an unlocked store takes gravel as readily as iron",
        )
    }

    /**
     * ⚠️ **A full store refuses at its own door**, which is what keeps the door and the route
     * saying one thing. The network stops routing at it because `wanted` is nought; it also stops
     * *accepting* for the same reason and by the same read, rather than by a second opinion in the
     * delivery path.
     */
    @Test
    fun `a full store admits nothing`() {
        val full = Acceptance.upTo(0L)
        assertTrue(full.isSatisfied, "no room left is the same shape as a finished site")
        assertFalse(full.admits(iron(1L)), "a full store took a delivery")
        assertTrue(full.takesAnything, "and it is still unfussy — it is full, not picky")
    }

    @Test
    fun `a construction site wants exactly what it is short by`() {
        val site = Acceptance.forBill(railBill, 400L)
        assertFalse(site.isUnlimited, "a site is the one sink with a final total")
        assertFalse(site.isSatisfied)
        assertTrue(site.wanted == 400L, "it wants the shortfall, not the whole bill")
    }

    @Test
    fun `a finished site takes nothing at all`() {
        val done = Acceptance.forBill(railBill, 0L)
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
        val site = Acceptance.forBill(railBill, railBill.total)
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

        val hungry = Acceptance.forBill(railBill, railBill.total)
        val full = Acceptance.forBill(railBill, 0L)

        assertTrue(hungry.admits(rightStuff))
        assertFalse(hungry.admits(wrongStuff), "hungry, but not indiscriminate")
        assertFalse(full.admits(rightStuff), "the right stuff, but there is nowhere left to put it")
        assertFalse(full.admits(wrongStuff))
    }
}
