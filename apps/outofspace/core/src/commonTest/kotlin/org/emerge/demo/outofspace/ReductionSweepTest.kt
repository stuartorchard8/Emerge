package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.REDUCTIONS
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.fluid
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.oxidise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reduction through the actual sweep — increment 5 of `PLAN_ambient_chemistry.md`.
 *
 * `ReductionTest` proves the table's arithmetic; this proves the pass *runs* it, which is a
 * genuinely separate claim. A row can balance perfectly and still never fire, because the sweep
 * rejected the tile on a temperature gate, or looked the reagent up in the wrong place, or never
 * grouped it at all — and every one of those failures looks exactly like "nothing happened", which
 * is also what a correctly cold tile looks like.
 *
 * Deliberately at the layer rather than through the reducer: no vessel, no belts, no air unless the
 * test asks for some. That is what makes the vacuum cases expressible at all — a sealed vessel is
 * full of oxygen, and the interesting thing about a reduction is what it does without any.
 */
class ReductionSweepTest {

    private val tiles = 16
    private val tile = TileIndex(3)

    private fun layerWith(vararg stuff: Pair<Species, Long>): StuffLayer {
        val layer = StuffLayer.empty(tiles)
        for ((species, mass) in stuff) layer[tile, species] = mass
        return layer
    }

    /** Put the tile at [kelvin], whatever it happens to hold. */
    private fun StuffLayer.heatTo(kelvin: Int) = setEnergy(tile, heatCapacityAt(tile) * kelvin)

    /** No air at all — the condition every one of these reactions actually wants. */
    private fun vacuum() = MassArray(tiles)

    private fun sweep(layer: StuffLayer, air: MassArray = vacuum(), passes: Int = 1) {
        repeat(passes) { oxidise(layer, air, null) }
    }

    private val kg = Budget.KILOGRAM

    // ── Each row fires ───────────────────────────────────────────────────────

    @Test
    fun `every row in the table actually runs when its conditions are met`() {
        // The sweep-level counterpart to the table tests: fed both reagents, in a vacuum, well above
        // its onset, every row must consume something. A row that balances and never fires is the
        // failure mode this whole file exists for — and it is invisible from the table's side.
        //
        // ⚠️ **Against a control that withholds the reductant, rather than against the 100 kg it
        // started with.** A reaction's products stay in the layer now, so the tile is no longer a
        // one-way street: at this temperature the algae the photosynthesis row needs as a catalyst
        // is *also* pyrolysing, and the four waters that puts back hid the water the row consumed.
        // The row was firing perfectly well and the measurement could not see it.
        //
        // Withholding the one reagent the row cannot run without isolates its contribution from
        // every other reaction in the tile, whatever they happen to be — the same two-runs-differing
        // -in-one-thing construction as `in air the reagent burns instead of reducing`, and it does
        // not need to know what the interference was.
        for (reaction in REDUCTIONS) {
            fun swept(withReductant: Boolean): StuffLayer {
                val layer = layerWith(
                    *listOfNotNull(
                        reaction.oxide to 100L * kg,
                        if (withReductant) reaction.reductant to 100L * kg else null,
                        reaction.catalyst?.to(100L * kg),
                    ).toTypedArray(),
                )
                layer.heatTo(reaction.onsetKelvin * 2)
                sweep(layer)
                return layer
            }

            val layer = swept(withReductant = true)
            assertTrue(
                layer[tile, reaction.oxide] < swept(withReductant = false)[tile, reaction.oxide],
                "${reaction.oxide} + ${reaction.reductant} never fired in the sweep",
            )
            assertTrue(
                layer[tile, reaction.reductant] < 100L * kg,
                "${reaction.oxide} + ${reaction.reductant} consumed oxide but no reagent",
            )
            // ⚠️ **Every product, including the gaseous ones.** They used to be vented into the air
            // as they were made and so were invisible here; they stay in the layer now until
            // [org.emerge.demo.outofspace.world.offGas] finds them a room, and this sweep is the
            // layer on its own with no room anywhere near it.
            for ((product, _) in reaction.products) {
                assertTrue(
                    layer[tile, product] > 0L,
                    "${reaction.oxide} + ${reaction.reductant} produced no $product",
                )
            }
        }
    }

    @Test
    fun `an oxide with no reductant sits there for ever`() {
        // The statement that this is a *reagent* reaction and not a hot dial. Rutile at 3000 K with
        // nothing to take its oxygen is rutile.
        val layer = layerWith(Species.Rutile to 100L * kg)
        layer.heatTo(3000)
        sweep(layer, passes = 8)

        assertEquals(100L * kg, layer[tile, Species.Rutile], "titania gave up its oxygen to nothing at all")
        assertEquals(0L, layer[tile, Species.Titanium], "titanium appeared from nowhere")
    }

    // ── Contention is per reagent ────────────────────────────────────────────

    @Test
    fun `a reagent shortage does not slow a row that eats something else`() {
        // The structural claim of the increment, made observable. If the sweep pooled every reagent
        // against one number, the titanium row would be starved by a shortage of something it does
        // not eat — and would still *look* like it was working, just slower, which is why this is
        // worth an explicit test rather than a reading of the code.
        //
        // ⚠️ **No ilmenite in this fixture, deliberately.** The ilmenite row *produces* rutile, so a
        // carbon shortage changes how much titania is standing there to be reduced — and the
        // comparison would measure that cascade rather than the contention. The confound is real
        // (see the ⚠️ in `oxidise`) and the fixture is what keeps it out.
        fun titaniaReduced(carbon: Long): Long {
            val layer = layerWith(
                Species.Quartz to 100L * kg,
                Species.Carbon to carbon,
                Species.Rutile to 100L * kg,
                Species.Magnesium to 100L * kg,
            )
            layer.heatTo(4000)
            sweep(layer)
            return 100L * kg - layer[tile, Species.Rutile]
        }

        assertEquals(
            titaniaReduced(100L * kg),
            titaniaReduced(1L * Budget.GRAM),
            "a carbon shortage changed how much titania was reduced by magnesium",
        )
    }

    @Test
    fun `two rows after the same reagent both get a share of it`() {
        // The other half: quartz and ilmenite are both after the carbon, there is nowhere near
        // enough, and the apportionment must feed both rather than handing the lot to whichever the
        // table happens to list first. That is the leftward bias `oxidise` is required to avoid, and
        // it would be invisible — the sweep would look like it was working, for one of the two rows.
        val layer = layerWith(
            Species.Quartz to 100L * kg,
            Species.Ilmenite to 100L * kg,
            Species.Carbon to 1L * Budget.GRAM,
        )
        layer.heatTo(4000)
        sweep(layer)

        assertTrue(layer[tile, Species.Silicon] > 0L, "the quartz row got none of the carbon")
        assertTrue(layer[tile, Species.Iron] > 0L, "the ilmenite row got none of the carbon")
    }

    @Test
    fun `a shared reagent is never oversubscribed`() {
        // Jacobi's actual guarantee: the two carbon rows between them may take all of it and not one
        // microgram more. Nothing else in the sweep would notice if they did — the layer would simply
        // go negative, which reads back as an enormous mass.
        val layer = layerWith(
            Species.Quartz to 1_000L * kg,
            Species.Ilmenite to 1_000L * kg,
            Species.Carbon to 5L * Budget.GRAM,
        )
        layer.heatTo(4000)
        sweep(layer)

        assertTrue(layer[tile, Species.Carbon] >= 0L, "the carbon went negative — it was oversubscribed")
    }

    // ⛔ **`the Boudouard reaction puts itself out` has moved to `BoudouardTest`**
    // (`PLAN_unified_reactions.md`, increment 4). The row is no longer a [Reduction].
    //
    // ⚠️ **And the test it was is worth remembering.** It placed carbon dioxide *directly in a cargo
    // layer* at 1400 K and proved a lovely property about it — that the row is its own brake. No
    // vessel can ever be in that state: CO2 is evicted from a cargo layer above 304 K, so `offGas`
    // empties it eleven hundred kelvin before the onset. A green test, about a real behaviour, in a
    // configuration the simulation cannot reach. That is the exact pattern the plan exists to stop,
    // and it was sitting inside the suite that was supposed to catch it.

    // ── Oxygen is what stops it ──────────────────────────────────────────────

    @Test
    fun `in air the reagent burns instead of reducing`() {
        // "Reduction wants a vacuum" as an *outcome* rather than a rule. Nothing in `Reduction.kt`
        // mentions oxygen; the reductant is simply also a fuel, and the oxidation table is running
        // over the same tile. The same charge, twice, differing only in whether there is air.
        // ⚠️ **The carbon has to be the scarce thing** for this to show anything. With a hopper full
        // of graphite, burning a fraction of it still leaves far more than the quartz row can use, and
        // the two runs come out identical — which says the air is harmless rather than that the test
        // is badly sized. Scarce carbon is also the honest case: it is the reagent the player paid to
        // put there.
        fun run(air: MassArray): Long {
            val layer = layerWith(Species.Quartz to 100L * kg, Species.Carbon to 200L * Budget.GRAM)
            layer.heatTo(2400)
            oxidise(layer, air, null)
            return layer[tile, Species.Silicon]
        }

        val airy = MassArray(tiles)
        airy.add(tile, Fluid.Oxygen, 50L * kg)

        val inVacuum = run(vacuum())
        val inAir = run(airy)

        assertTrue(inVacuum > 0L, "no silicon was made even in a vacuum")
        assertTrue(
            inAir < inVacuum,
            "air made no difference: $inAir in air against $inVacuum in vacuum — is the carbon being " +
                "double-spent rather than contended?",
        )
    }

    // ── Conservation, through the sweep ──────────────────────────────────────

    @Test
    fun `the sweep conserves mass across the whole chain`() {
        // Everything the layer lost has to be exactly what the air gained — the two halves of the
        // crossing `ChemistryStep` exists to report. The chain makes carbon monoxide, so this is not
        // a closed layer, and a sweep that quietly dropped a product would look like a working
        // reduction with a slightly disappointing yield.
        val layer = layerWith(
            Species.Quartz to 200L * kg,
            Species.Periclase to 200L * kg,
            Species.Ilmenite to 200L * kg,
            Species.Rutile to 200L * kg,
            Species.Carbon to 50L * kg,
        )
        layer.heatTo(4000)

        val before = layer.massAt(tile)
        val air = vacuum()
        var venting = 0L
        repeat(16) { venting += oxidise(layer, air, null).toGasMass }
        val after = layer.massAt(tile)

        var inAir = 0L
        air.forEachFluid(tile) { _, mass -> inAir += mass }

        assertEquals(before - after, venting, "the layer lost a different mass than the step reported")
        assertEquals(venting, inAir, "what left the solids is not what arrived in the air")
    }

    @Test
    fun `titanium comes out of rocks and carbon, through the sweep and nothing else`() {
        // The acceptance test for the increment, and the answer to the question this began with. One
        // tile, a hopper's worth of ore and graphite, hot and airless — and no machine logic, no
        // recipe, no player. Just conditions.
        val layer = layerWith(
            Species.Quartz to 100L * kg,
            Species.Magnesite to 100L * kg,
            Species.Ilmenite to 100L * kg,
            Species.Carbon to 100L * kg,
        )

        val air = vacuum()
        // Held hot, because the endothermic rows cool their own feed and a decomposer's element is
        // what fights that. The temperature is the machine's job; this test is only about whether the
        // chemistry gets there.
        repeat(400) {
            layer.heatTo(2400)
            oxidise(layer, air, null)
        }

        assertTrue(layer[tile, Species.Periclase] > 0L, "the magnesite never calcined")
        assertTrue(layer[tile, Species.Silicon] > 0L, "no silicon was reduced out of the quartz")
        assertTrue(layer[tile, Species.Magnesium] > 0L, "the Pidgeon step never ran")
        assertTrue(layer[tile, Species.Rutile] > 0L, "the ilmenite never gave up its iron")
        assertTrue(layer[tile, Species.Iron] > 0L, "no iron came out of the ilmenite")
        assertTrue(
            layer[tile, Species.Titanium] > 0L,
            "NO TITANIUM. The chain is broken somewhere between a rock and a metal.",
        )
    }
}
