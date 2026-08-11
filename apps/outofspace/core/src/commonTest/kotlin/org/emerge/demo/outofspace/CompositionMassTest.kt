package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.biteCell
import org.emerge.demo.outofspace.world.capacityPerTileOf
import org.emerge.demo.outofspace.world.gramsPerTileOf
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.solidGramsPerTile
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

    private fun pure(species: Species): Mixture = Mixture.of(species to 1_000L)

    @Test
    fun `a pure rock weighs its species' density`() {
        for (species in Species.ALL) {
            val body = rock(pure(species))
            assertEquals(
                species.solidGramsPerTile * body.filled, body.massGrams,
                "a rock of pure $species",
            )
        }
    }

    @Test
    fun `density orders bodies the way the real materials do`() {
        val uranium = rock(pure(Species.Uranium)).massGrams
        val iron = rock(pure(Species.Iron)).massGrams
        val silica = rock(pure(Species.Silica)).massGrams
        val ice = rock(pure(Species.Water)).massGrams
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
        val half = Mixture.of(Species.Iron to 500L, Species.Silica to 500L)
        val ironDensity = Species.Iron.solidGramsPerTile
        val silicaDensity = Species.Silica.solidGramsPerTile

        val harmonic = 2L * ironDensity * silicaDensity / (ironDensity + silicaDensity)
        val arithmetic = (ironDensity + silicaDensity) / 2L

        assertEquals(harmonic, gramsPerTileOf(half), "half iron half silica by mass")
        assertTrue(harmonic < arithmetic, "the two formulas have to differ or this test proves nothing")
    }

    /** Heat capacity averages by mass, unlike density — warming a tile means warming each gram. */
    @Test
    fun `capacity is the mass-weighted specific heat of what is there`() {
        val mix = Mixture.of(Species.Iron to 700L, Species.Water to 300L)
        val perGram = 700L * Species.Iron.specificHeat + 300L * Species.Water.specificHeat
        // Divided last, as the implementation does — dividing the specific heat down to a per-gram
        // integer first throws away a fraction that a whole tile's worth of grams makes visible.
        assertEquals(gramsPerTileOf(mix) * perGram / 1000L, capacityPerTileOf(mix), "capacity of a wet iron rock")
    }

    @Test
    fun `a rock starts at ambient whatever it is made of`() {
        for (species in listOf(Species.Uranium, Species.Silica, Species.Water)) {
            assertEquals(Temperature.AMBIENT_KELVIN, rock(pure(species)).kelvin, "a $species rock")
        }
    }

    /** A dense rock is worth more ore a bite, because the bite is a whole tile of *that* rock. */
    @Test
    fun `a bite takes what a tile of that rock weighs`() {
        val heavy = rock(pure(Species.Uranium))
        val light = rock(pure(Species.Water))
        assertEquals(heavy.gramsPerTile, biteCell(heavy, heavy.cells.indexOfFirst { it }).grams)
        assertTrue(
            biteCell(heavy, heavy.cells.indexOfFirst { it }).grams >
                biteCell(light, light.cells.indexOfFirst { it }).grams,
            "a bite of uranium should outweigh a bite of ice",
        )
    }

    /** Eating a rock hollow returns exactly its mass — no crumb minted or lost on the way. */
    @Test
    fun `biting a rock to nothing yields exactly its mass`() {
        var body: RigidBody? = rock(Mixture.of(Species.Iron to 410L, Species.Silica to 300L))
        val whole = body!!.massGrams
        var taken = 0L
        while (body != null) {
            val bite = biteCell(body, body.cells.indexOfFirst { it })
            taken += bite.grams
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
            machineKind = MachineKind.Smelter,
            joules = 0L,
        )
        assertEquals(MachineKind.Smelter.material.gramsPerTile, fragment.massGrams)
        assertEquals(MachineKind.Smelter.material.capacityPerTile, fragment.capacity)
    }

    /**
     * The anchor. The scale in `Composition.kt` was chosen so the ore field's natural abundance
     * still weighs the 3 kg a tile every rock used to weigh, so that this change moves the *spread*
     * of rock masses and not the tuning of thrust, contact and ore budgets. Integer densities land
     * it a couple of grams under; a rock that drifts a percent off means the scale moved.
     */
    @Test
    fun `an average rock still weighs what every rock used to`() {
        val average = gramsPerTileOf(OutofspaceReducer.DEFAULT_ORE_BODY)
        val was = RigidBody.MATERIAL.gramsPerTile
        assertTrue(
            average > was * 99 / 100 && average < was * 101 / 100,
            "the natural-abundance rock weighs $average g a tile, against the $was g it used to",
        )
    }
}
