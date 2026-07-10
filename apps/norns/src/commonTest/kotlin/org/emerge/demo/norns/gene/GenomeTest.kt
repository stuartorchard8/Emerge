package org.emerge.demo.norns.gene

import org.emerge.demo.norns.bio.ChemistryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the genome (subsystem 2): the genome→phenotype bridge
 * (expression reproduces a known biochemistry), plus deterministic, bounded, both-parent
 * heredity. Constants are placeholders (DESIGN.md G1) — this proves the genetics MECHANISM.
 */
class GenomeTest {

    // hunger-network indices (same as the biochemistry subsystem's behavioural test)
    private val GLUCOSE = 0; private val HUNGER = 1
    private val FOOD = 0; private val METABOLISM = 1; private val HUNGER_DRIVE = 2

    private fun hungerGenome() = Genome(
        chemicalCount = 2,
        locusCount = 3,
        genes = listOf(
            HalfLifeGene(GLUCOSE, 5f),
            HalfLifeGene(HUNGER, 0f),
            EmitterGene(locus = METABOLISM, chemical = HUNGER, gain = 0.02f, threshold = 0f),
            EmitterGene(locus = FOOD, chemical = GLUCOSE, gain = 0.5f, threshold = 0f),
            ReactionGene(listOf(GLUCOSE to 1f, HUNGER to 1f), emptyList(), rate = 0.5f),
            ReceptorGene(chemical = HUNGER, locus = HUNGER_DRIVE, gain = 1f, threshold = 0f, nominal = 0f),
        ),
    )

    @Test
    fun expressedGenomeReproducesHungerRegulation() {
        // The whole point of a genome: expressing it yields a working phenotype. Run the
        // homeostatic loop from the EXPRESSED biochemistry and assert it regulates.
        val bio = hungerGenome().expressBiochemistry()

        val starving = ChemistryState(2, 3)
        repeat(100) { starving.locus[FOOD] = 0f; starving.locus[METABOLISM] = 1f; bio.tick(starving) }
        val fed = ChemistryState(2, 3)
        repeat(100) { fed.locus[FOOD] = 1f; fed.locus[METABOLISM] = 1f; bio.tick(fed) }

        assertTrue(starving.locus[HUNGER_DRIVE] > 0.5f, "expressed genome should let hunger climb when starving")
        assertTrue(fed.locus[HUNGER_DRIVE] < 0.2f, "expressed genome should regulate hunger when fed")
        assertTrue(fed.locus[HUNGER_DRIVE] < starving.locus[HUNGER_DRIVE], "feeding should beat starving")
    }

    @Test
    fun expressBuildsHalfLifeTable() {
        val genome = Genome(2, 0, listOf(HalfLifeGene(0, 10f))) // chem 1 has no half-life gene
        val bio = genome.expressBiochemistry()
        val s = ChemistryState(2, 0)
        s.concentration[0] = 1f; s.concentration[1] = 1f
        repeat(10) { bio.tick(s) }
        assertTrue(kotlin.math.abs(s.concentration[0] - 0.5f) < 1e-4f, "gene-set half-life decays: ${s.concentration[0]}")
        assertEquals(1f, s.concentration[1], "chemical with no half-life gene does not decay")
    }

    @Test
    fun crossoverIsDeterministic() {
        val mother = hungerGenome(); val father = hungerGenome()
        val a = mother.crossover(father, GeneRng(7)).genes
        val b = mother.crossover(father, GeneRng(7)).genes
        assertEquals(a, b, "same parents + same seed -> identical child")
    }

    @Test
    fun crossoverInheritsFromBothParents() {
        // Distinguishable, structurally-aligned parents: mother gain=1, father gain=2 everywhere.
        val n = 16
        val mother = Genome(1, n, (0 until n).map { EmitterGene(locus = it, chemical = 0, gain = 1f, threshold = 0f) })
        val father = Genome(1, n, (0 until n).map { EmitterGene(locus = it, chemical = 0, gain = 2f, threshold = 0f) })
        val child = mother.crossover(father, GeneRng(99)).genes

        var fromMother = 0; var fromFather = 0
        for (g in child) when ((g as EmitterGene).gain) {
            1f -> fromMother++
            2f -> fromFather++
            else -> error("child gene came from neither parent: $g")
        }
        assertEquals(n, fromMother + fromFather)
        assertTrue(fromMother > 0 && fromFather > 0, "both parents should contribute (mother=$fromMother father=$fromFather)")
    }

    @Test
    fun mutationIsDeterministic() {
        val g = hungerGenome()
        val a = g.mutate(rate = 1f, rng = GeneRng(42)).genes
        val b = g.mutate(rate = 1f, rng = GeneRng(42)).genes
        assertEquals(a, b, "same genome + same seed -> identical mutation")
    }

    @Test
    fun mutationStaysWithinBoundsOverManyGenerations() {
        // Drift a single gene for many generations; bounded perturbation must keep it sane.
        var genome = Genome(1, 1, listOf(EmitterGene(locus = 0, chemical = 0, gain = 7.9f, threshold = 0.95f)))
        val rng = GeneRng(123)
        repeat(1000) {
            genome = genome.mutate(rate = 1f, rng = rng)
            val gene = genome.genes[0] as EmitterGene
            assertTrue(gene.gain in -GeneRng.MAX_GAIN..GeneRng.MAX_GAIN, "gain escaped bounds: ${gene.gain}")
            assertTrue(gene.threshold in 0f..1f, "threshold escaped [0,1]: ${gene.threshold}")
        }
    }

    @Test
    fun immutableGenesAreNeverMutated() {
        val locked = EmitterGene(locus = 0, chemical = 0, gain = 3f, threshold = 0.5f, header = GeneHeader(mutable = false))
        var genome = Genome(1, 1, listOf(locked))
        val rng = GeneRng(5)
        repeat(100) { genome = genome.mutate(rate = 1f, rng = rng) }
        assertEquals(locked, genome.genes[0], "an immutable gene must never change")
    }
}
