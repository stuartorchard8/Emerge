package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.fixtureCell
import org.emerge.demo.cyto.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Where a gene sits in the list must not change what it gets.**
 *
 * A tick's resources are divided among the active genes *before* any of them run, from an immutable
 * tick-start snapshot ([CytoBiologyCore.runGenes]), precisely so a gene never has to know what its
 * neighbours did that tick. If that holds, permuting the genome is a no-op: the same cell in the same world
 * ends the run with the same chemistry whatever order its genes are written in.
 *
 * These tests permute and compare. They deliberately do NOT restate *how much* each gene should get — that
 * arithmetic is pinned elsewhere, and duplicating it here would mean editing this file for every tuning
 * change, which is how an ordering bug hides.
 *
 * ## Two traps this fixture is built to avoid
 *
 * **A world where nothing happens.** Two identical no-ops compare equal, so an inert fixture makes every
 * test here pass while proving nothing. [GROWER] is therefore a *bond*-powered pair, verified to roughly
 * double the cell's biomass over the run, and [aGeneticallyInertCellOnlyDecays] pins the contrast against a
 * genome-less control that decays instead. If the guard test ever fails, distrust the rest of the file.
 *
 * **A gene that cannot start.** `Bond X Y : Convert XY` consumes `XY` from the **tick-start** cytoplasm, so
 * a cell holding none can never run it: the bond that would make `XY` is only paid for by the convert that
 * needs it. Both products are seeded below so each gene is genuinely able to run, and the question under
 * test is how the contended monomers are split — not whether a gene can bootstrap.
 */
class GeneOrderIndependenceTest {

    /** Two synthesis growers contending for every unit of `r` and `g`, differing only in bond direction —
     *  `rg` is Redreen, `gr` is Greed. This is the shape Toby reported. */
    private val GROWER = """
        # genome 4
        Bond r g : rg < 3000 : Convert rg
        Bond g r : gr < 3000 : Convert gr
    """.trimIndent()

    private fun parse(src: String) = GeneCodec.parse(src)

    /** Run [genome] in a lit, well-stocked world for [ticks]; returns the cell's (cytoplasm, biomass).
     *  Both bond products are seeded so neither gene is bootstrap-blocked (see the class doc). */
    private fun run(genome: List<Gene>, ticks: Int = 50): Pair<Map<String, Int>, Map<String, Int>> {
        val f = CytoTestWorld.empty()
            .cell(
                "a",
                genome = genome,
                cytoplasm = mapOf("r" to 4000, "g" to 4000, "b" to 4000, "rg" to 2000, "gr" to 2000, "gb" to 2000),
                biomass = mapOf("rg" to 3000),
                light = CytoTestWorld.Light.Full,
            )
            .matter(level = 40)
            .build()
        CytoWorldConfig.applyFrom(f.scenario)
        val c = CytoController(scenario = f.scenario).also { it.loadFixture(f) }
        val id = c.fixtureCell(f, "a")
        repeat(ticks) { c.tick(1f) }
        val cell = c.tick(0f).state.components.getTable<CytoCellComponent>().asMap()[id]
            ?: error("the cell died during the run — the fixture is not measuring gene work")
        return cell.cytoplasm.toMap() to cell.biomass.toMap()
    }

    private fun biomassOf(genome: List<Gene>) = totalBiomass(run(genome).second)

    /**
     * The guard the rest of the file rests on: with these genes the cell GROWS, and without them it decays.
     * Measured, not assumed — an earlier pass at this investigation read a founder's untouched starter
     * biomass as if it were the genes' output and drew exactly the wrong conclusion from it.
     */
    @Test fun aGeneticallyInertCellOnlyDecays() {
        val grown = biomassOf(parse(GROWER))
        val inert = biomassOf(emptyList())
        assertTrue(inert < 6000, "a genome-less cell should decay from its 6000 seed, got $inert")
        assertTrue(grown > 6000, "the growers should ADD biomass past the 6000 seed, got $grown")
        assertTrue(grown > inert * 2, "growth ($grown) must dwarf the decaying control ($inert)")
    }

    /** Reversing a two-gene genome must change nothing at all. */
    @Test fun twoContendingGenesAreOrderIndependent() {
        val g = parse(GROWER)
        val (cytoA, bioA) = run(g)
        val (cytoB, bioB) = run(g.reversed())
        assertEquals(cytoA, cytoB, "the contended monomers were split differently when the order changed")
        assertEquals(bioA, bioB, "biomass differed when the contending genes were swapped")
    }

    /**
     * Neither contender may be starved: two genes with an equal claim on the same monomers must end up
     * with comparable holdings. A 100/0 or 90/10 split is the ordering bug this file exists to catch.
     *
     * Compared as **final holdings**, not as "how much did each add". `rg` is seeded into biomass (a cell
     * has to start made of something) while `gr` starts at zero, so a built-since-t0 measure charges the
     * seed against `rg` — it ends slightly BELOW its seed here, because both genes converge on the same
     * `< 3000` gate and decay does the rest. What matters is that the two arrive at the same place.
     */
    @Test fun twoEqualClaimsOnTheSameMonomersEndUpEven() {
        val (_, bio) = run(parse(GROWER))
        val rg = bio["rg"] ?: 0
        val gr = bio["gr"] ?: 0
        assertTrue(rg > 0 && gr > 0, "both genes must hold something: rg=$rg gr=$gr")
        val ratio = maxOf(rg, gr).toDouble() / minOf(rg, gr)
        assertTrue(ratio < 1.1, "equal claims got a lopsided split: rg=$rg gr=$gr (ratio $ratio)")
    }

    /**
     * A three-gene genome has 6 orderings; all must agree — to within the **integer-division remainder**.
     *
     * Exact equality does not hold and shouldn't be asserted: a share is `snap.count(sp) / n`, which
     * truncates, and a synthesis gene consumes its reactants at the *ceiling* `⌈k/gP1⌉`. Both leave a
     * sub-unit residue whose owner depends on position, so 50 ticks of compounding lands orderings within
     * about one part in a thousand of each other rather than on the same integer. That is rounding, not
     * precedence — [twoEqualClaimsOnTheSameMonomersEndUpEven] is what guards the thing that matters.
     */
    @Test fun everyPermutationOfAThreeGeneGenomeAgrees() {
        val g = parse("$GROWER\nLight : gb < 3000 : Convert gb")
        val (_, expected) = run(g)
        for (p in permutations(g)) {
            val (_, got) = run(p)
            assertEquals(expected.keys, got.keys, "this ordering built different species:\n${GeneCodec.serialize(p)}")
            for ((sp, want) in expected) {
                val diff = kotlin.math.abs(want - (got[sp] ?: 0))
                assertTrue(
                    diff <= 1 + want / 500,
                    "'$sp' moved by $diff (want $want, got ${got[sp]}) — more than rounding:\n${GeneCodec.serialize(p)}",
                )
            }
        }
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs)
        else xs.indices.flatMap { i ->
            permutations(xs.filterIndexed { j, _ -> j != i }).map { listOf(xs[i]) + it }
        }
}
