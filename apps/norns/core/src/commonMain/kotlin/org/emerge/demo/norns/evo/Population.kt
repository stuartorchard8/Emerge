package org.emerge.demo.norns.evo

import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome

/** One member of a [Population]: its heritable [genome] (the phenotype is expressed from it). */
class Individual(val genome: Genome)

/**
 * A breeding population of creatures. [evolve] runs one generation: score every member, let the
 * fitter ones breed (genome [crossover][Genome.crossover] + [mutate][Genome.mutate]), and refill
 * to the original size. Over generations a heritable trait under selection adapts — evolution,
 * the open-ended engine behind Creatures.
 *
 * Selection here is **explicit truncation** (top half breed) against a supplied fitness function;
 * the fully faithful Creatures dynamic is *implicit* selection — embodied creatures that survive
 * and mate in the world pass their genes, those that die don't. That implicit loop combines this
 * subsystem with the embodied creature (subsystem 6) and needs the brain gene-encoded (G5); it's
 * deferred — DESIGN.md gap G9.
 */
class Population(val members: List<Individual>) {
    val size: Int get() = members.size

    /**
     * Produces the next generation, deterministic in [rng]: rank by [fitness], take the fitter
     * half as breeders, and breed [size] offspring from random breeder pairs (each
     * `parentA.reproduceWith(parentB)` — crossover then mutate at [mutationRate]).
     */
    fun evolve(mutationRate: Float, rng: GeneRng, fitness: (Genome) -> Float): Population {
        require(members.size >= 2) { "need at least two members to breed" }
        val ranked = members.sortedByDescending { fitness(it.genome) }
        val breeders = ranked.take(maxOf(2, members.size / 2))
        val next = ArrayList<Individual>(members.size)
        repeat(members.size) {
            val a = breeders[rng.nextInt().mod(breeders.size)].genome
            val b = breeders[rng.nextInt().mod(breeders.size)].genome
            next.add(Individual(a.reproduceWith(b, mutationRate, rng)))
        }
        return Population(next)
    }
}
