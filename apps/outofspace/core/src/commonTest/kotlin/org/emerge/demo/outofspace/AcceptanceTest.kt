package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.SpeciesFilter
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

    // ── An order, or an allowance ─────────────────────────────────────────────

    /**
     * ⛔ **A sink's own door still takes what it ordered less of than its tank holds.**
     *
     * [Acceptance.wanted] has always had two meanings a paragraph apart — *"a construction site is
     * done for good; a store is merely full"* — and nothing acted on the difference, so `sinkAdmits`
     * refused every satisfied sink even though its own note says a door asks *"kind, never
     * quantity … refusing it for being surplus does not save it, it only strands it one tile
     * earlier."* A locked kiln orders each reagent to the recipe's ratio and keeps a tank several
     * times that, so it is the one sink where satisfied and full genuinely come apart — and its
     * surplus was stranded on the tile outside its mouth, in front of the reagent that would have
     * let it drain. Stu's `over_fill.txt`.
     */
    @Test
    fun `a sink that ordered to a target still takes a surplus at its own door`() {
        val ordered = Acceptance.filtered(
            SpeciesFilter(Species.Iron, pure = true),
            wanted = 0L,
            doorTakesSurplus = true,
        )

        assertTrue(ordered.isSatisfied, "the order is filled")
        assertFalse(ordered.admits(iron(1_000L)), "so nothing more should be SENT")
        assertTrue(ordered.admitsAtDoor(iron(1_000L)), "but a lump that arrived anyway still fits")
    }

    /**
     * ⛔ **And an allowance does not, which is why this is a flag rather than a change to the door.**
     *
     * A docking port whose sell permission is spent must go on letting cargo cross its mouth toward
     * a tank beyond it. Made to swallow the surplus it would sell what the player never allowed —
     * the `dock.txt` failure arrived at from the far side, so the default has to be the strict one.
     */
    @Test
    fun `an allowance refuses a surplus at its door, and that is the default`() {
        val permitted = Acceptance.filtered(SpeciesFilter(Species.Iron, pure = true), wanted = 0L)
        assertFalse(permitted.doorTakesSurplus, "the strict reading has to be what a sink gets for free")
        assertFalse(permitted.admitsAtDoor(iron(1_000L)), "a spent permission swallowed a delivery")

        // A construction site is the other allowance, and it says so through its own factory.
        assertFalse(
            Acceptance.forBill(railBill, 0L).admitsAtDoor(iron(1_000L)),
            "a finished site swallowed a delivery it had no claim on",
        )
    }

    /** Kind is still kind: the flag drops the quantity question and nothing else. */
    @Test
    fun `taking a surplus is not taking anything`() {
        val ordered = Acceptance.filtered(
            SpeciesFilter(Species.Iron, pure = true),
            wanted = 0L,
            doorTakesSurplus = true,
        )
        assertFalse(
            ordered.admitsAtDoor(Mixture.of(Species.Quartz to 1_000L, energy = 0)),
            "the door forgot what it is for",
        )
    }
}
