package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.massPerTile
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.machine.biteCell
import org.emerge.demo.outofspace.world.capacityPerTileOf
import org.emerge.demo.outofspace.world.massPerTileOf
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.solidMassPerTile
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.massPerTile
import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.TileEnergy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A body weighs what it is made of, tile by tile.
 *
 * Before this, every rock in the world was firebrick: the same 3 kg a tile whether it assayed at
 * uranium or at ice, so the ore field's variety was a colour and a payout and nothing you could feel
 * pushing one around. The assertions here are the model, not measurements — each expected value is
 * worked out from the species table in the test rather than pinned to a number the code printed.
 */
class CompositionMassTest {

    private fun rock(composition: Mixture, radius: Int = 2): RigidBody =
        RigidBody.rockBlob(radius = radius, positionX = 0L, positionY = 0L, composition = composition)

    private fun pure(species: Species): Mixture = Mixture.of(species to 1_000L, energy = 0)

    @Test
    fun `a pure rock weighs its species' density`() {
        for (species in Species.ALL) {
            val body = rock(pure(species))
            assertEquals(
                species.solidMassPerTile * body.filled, body.mass,
                "a rock of pure $species",
            )
        }
    }

    @Test
    fun `density orders bodies the way the real materials do`() {
        val uranium = rock(pure(Species.Uranium)).mass
        val iron = rock(pure(Species.Iron)).mass
        val silica = rock(pure(Species.Quartz)).mass
        val ice = rock(pure(Species.Water)).mass
        assertTrue(uranium > iron, "uranium ($uranium g) should outweigh iron ($iron g)")
        assertTrue(iron > silica, "iron ($iron g) should outweigh silica ($silica g)")
        assertTrue(silica > ice, "silica ($silica g) should outweigh ice ($ice g)")
    }

    /**
     * The one that catches the tempting wrong formula. Two things in a tile share its *volume*, so
     * a half-and-half mixture by mass is denser than the arithmetic mean of the two densities would
     * say — the light half takes up more than half the room. Harmonic, not arithmetic.
     */
    @Test
    fun `a mixture's density is the harmonic mean, not the arithmetic one`() {
        val half = Mixture.of(Species.Iron to 500L, Species.Quartz to 500L, energy = 0L)
        val ironDensity = Species.Iron.solidMassPerTile
        val silicaDensity = Species.Quartz.solidMassPerTile

        val harmonic = 2L * ironDensity * silicaDensity / (ironDensity + silicaDensity)
        val arithmetic = (ironDensity + silicaDensity) / 2L

        // Within a few parts per million: both sides are integer arithmetic over tonne-scale
        // densities, rounding in different orders. The claim is the formula, not the last digit.
        assertTrue(
            close(harmonic, massPerTileOf(half)),
            "half iron half silica by mass: expected about $harmonic, got ${massPerTileOf(half)}",
        )
        assertTrue(harmonic < arithmetic, "the two formulas have to differ or this test proves nothing")
    }

    /** Heat capacity averages by mass, unlike density — warming a tile means warming each gram. */
    @Test
    fun `capacity is the mass-weighted specific heat of what is there`() {
        val mix = Mixture.of(Species.Iron to 700L, Species.Water to 300L, energy = 0)
        val perGram = 700L * Species.Iron.specificHeat + 300L * Species.Water.specificHeat
        // Divided last, as the implementation does — dividing the specific heat down to a per-gram
        // integer first throws away a fraction that a whole tile's worth of mass makes visible.
        val expected = massPerTileOf(mix) * perGram / 1000L
        assertTrue(
            close(expected, capacityPerTileOf(mix)),
            "capacity of a wet iron rock: expected about $expected, got ${capacityPerTileOf(mix)}",
        )
    }

    /**
     * Step 6 of PLAN_unit_rescale.md. `capacityPerTileOf` is an *intensive* quantity: it is what one
     * tile of a composition costs to warm, and doubling the sample cannot change it. That makes it
     * its own oracle — no literal to pin, just the same mixture stated at wildly different totals.
     *
     * It used to fail, at a total the old code could reach two different ways — `Σ mass ×
     * specificHeat` overflows on its own at ~2.2e15, and the product with a tile of solid goes
     * earlier still, worst case pure water. The symptom was a *negative* heat capacity rather than
     * an exception, so this sweeps water specifically rather than a mixture that reads more typical
     * but never gets there.
     *
     * ⚠️ This is a **contract** test, not an overflow-headroom test, and the difference matters
     * because the first version of it was the latter and was wrong. Nothing passes tonnages like
     * these, and — corrected — nothing ever will: proportions are not masses, so the mass-unit
     * rescale does not move this number. What the sweep pins is that the function depends on the
     * ratios in a composition and not on the units they were stated in, which is the only reading
     * under which "a tile of water" is a well-posed question at all.
     */
    @Test
    fun `what a tile costs to warm does not depend on how much of it you were handed`() {
        val recipe = listOf(Species.Water to 1_000L)
        // 1 kg to 10,000 tonnes — comfortably past the 2,900 tonnes where the old product wrapped.
        val reference = capacityPerTileOf(Mixture.of(*recipe.toTypedArray(), energy = 0))
        assertTrue(reference > 0L, "the reference capacity itself must be positive, got $reference")
        for (scale in listOf(1L, 1_000L, 1_000_000L, 10_000_000L)) {
            val scaled = Mixture.of(*recipe.map { (s, g) -> s to g * scale }.toTypedArray(), energy = 0)
            val actual = capacityPerTileOf(scaled)
            assertTrue(
                close(reference, actual),
                "the same water at ${scale}x: a tile of it must still cost about $reference, got $actual",
            )
        }
    }

    @Test
    fun `a rock starts at ambient whatever it is made of`() {
        for (species in listOf(Species.Uranium, Species.Quartz, Species.Water)) {
            assertEquals(Temperature.AMBIENT_KELVIN, rock(pure(species)).kelvin, "a $species rock")
        }
    }

    /** A dense rock is worth more ore a bite, because the bite is a whole tile of *that* rock. */
    @Test
    fun `a bite takes what a tile of that rock weighs`() {
        val heavy = rock(pure(Species.Uranium))
        val light = rock(pure(Species.Water))
        assertEquals(heavy.massPerTile, biteCell(heavy, heavy.cells.indexOfFirst { it }).mass)
        assertTrue(
            biteCell(heavy, heavy.cells.indexOfFirst { it }).mass >
                biteCell(light, light.cells.indexOfFirst { it }).mass,
            "a bite of uranium should outweigh a bite of ice",
        )
    }

    /** Eating a rock hollow returns exactly its mass — no crumb minted or lost on the way. */
    @Test
    fun `biting a rock to nothing yields exactly its mass`() {
        var body: RigidBody? = rock(Mixture.of(Species.Iron to 410L, Species.Quartz to 300L, energy = 0))
        val whole = body!!.mass
        var taken = 0L
        while (body != null) {
            val bite = biteCell(body, body.cells.indexOfFirst { it })
            taken += bite.mass
            body = bite.body
        }
        assertEquals(whole, taken, "the rock and the ore ledger disagree")
    }

    /** A fragment is its casing, not ore — the same tile weight the machine had on the deck. */
    @Test
    fun `a fragment weighs its machine's material`() {
        val fragment = RigidBody(
            kind = BodyKind.FRAGMENT,
            width = 1, height = 1, cells = booleanArrayOf(true),
            positionX = 0L, positionY = 0L, impulseX = 0L, impulseY = 0L,
            machineKind = DeckMachineKind.Smelter,
            energy = TileEnergy.uniform(1, 0L),
        )
        assertEquals(DeckMachineKind.Smelter.massPerTile, fragment.mass)
        assertEquals(DeckMachineKind.Smelter.capacityPerTile, fragment.capacity)
    }

    /**
     * No scale factor anywhere: a tile of ore weighs what that much ore weighs.
     *
     * Stated against the arithmetic a person would do by hand — the ore field assays about 4.3
     * tonnes a cubic metre, and a tile is [TILE_LITRES] of room — because the point of the number is
     * that you *can* check it by hand. This is the assertion that fails if anyone reintroduces a
     * fudge factor between real densities and the world.
     *
     * ⚠️ The band moved from 4600..4900 when [OutofspaceReducer.DEFAULT_ORE_BODY] was restated in
     * minerals: native copper at 8960 kg/m³ became chalcopyrite at 4200, and titanium metal at 4510
     * became ilmenite at 4720. **The band is not the thing under test** — it is a sanity range on a
     * *chosen* input, and the input genuinely changed. What is under test is the absence of a scale
     * factor, and that is why the range is wide and hand-derived rather than pinned to a digit.
     */
    @Test
    fun `a tile of ore weighs what that much ore weighs`() {
        val perTile = massPerTileOf(OutofspaceReducer.DEFAULT_ORE_BODY)
        // Out of Budget's unit and into mass first. A gram per litre *is* a kilogram per cubic
        // metre, so once the mass is in mass the rest is the identity the KDoc describes — but
        // while one integer was one gram that division was invisible, and the assay read in
        // millions of tonnes the moment the unit moved.
        val kgPerCubicMetre = perTile / Budget.GRAM / TILE_LITRES
        assertTrue(
            kgPerCubicMetre in 4_200L..4_500L,
            "the ore field assays at $kgPerCubicMetre kg/m3, which is not a rock",
        )
    }

    /** A rock is solid; a machine is a shell with air in it. The one must outweigh the other. */
    @Test
    fun `a boulder outweighs the ship's own fabric, tile for tile`() {
        val oreTile = massPerTileOf(OutofspaceReducer.DEFAULT_ORE_BODY)
        // One of each hierarchy: the fabric is split across two lists mid-migration, and the claim
        // is about the fabric rather than about either list.
        for ((label, perTile) in listOf(
            DeckMachineKind.Smelter.label to DeckMachineKind.Smelter.massPerTile,
            MachineKind.Rail.label to MachineKind.Rail.massPerTile,
        )) {
            assertTrue(
                oreTile > perTile * 4,
                "a tile of ore ($oreTile g) should dwarf a tile of $label ($perTile g)",
            )
        }
        for (kind in listOf(DeckMachineKind.Hull)) {
            assertTrue(
                oreTile > kind.massPerTile * 4,
                "a tile of ore ($oreTile g) should dwarf a tile of ${kind.label} (${kind.material.massPerTile} g)",
            )
        }
    }

    /**
     * A length of conduit's bill of materials weighs that length of conduit.
     *
     * The twin of `CasingMassTest`, which makes the same claim for every [DeckMachineKind]. It used
     * to be stated over `MachineKind`, back when that enum named buildings; every one of those has
     * moved to the deck and `MachineKind` names only conduits now, so this is what is left of it —
     * and it is a claim nothing else was making.
     */
    @Test
    fun `a length of conduit's bill of materials weighs that conduit`() {
        for (conduit in Conduit.entries) {
            val bom = conduitBillOfMaterials(conduit)
            assertEquals(conduit.massPerTile, bom.total, "${conduit.label} bill of materials")
            for (species in Species.ALL) {
                if (conduit.material.composition[species] == 0L) {
                    assertEquals(0L, bom[species], "${conduit.label} should contain no $species")
                }
            }
        }
    }

    /** Parts per million: integer arithmetic over tonne-scale numbers, rounding in two orders. */
    private fun close(a: Long, b: Long): Boolean {
        val slack = (if (a > b) a - b else b - a) * 1_000_000L
        return a != 0L && slack / a < 10L
    }
}
