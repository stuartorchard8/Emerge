package org.emerge.demo.norns.bio

/**
 * The mutable biochemical state of one creature: a vector of [concentration]s (one per chemical
 * species) plus a [locus] bus — the generic float channels through which the rest of the
 * creature couples to its chemistry, mirroring Creatures' "loci".
 *
 * A **locus** is an integration point read or written by other subsystems: sensors and organs
 * write loci that [Emitter]s turn into chemicals; [Receptor]s turn chemicals back into loci that
 * the brain, organs, and drives read. Keeping this coupling generic (a flat float array, not
 * typed fields) is what lets the biochemistry engine stay ignorant of brain/biology/world —
 * they just agree on locus indices.
 *
 * Concentrations live in `[0, MAX_CONCENTRATION]`. Loci are unclamped here; consumers clamp to
 * their own ranges. All state is plain floats so a tick is deterministic and trivially
 * snapshot/restore-able.
 */
class ChemistryState(val chemicalCount: Int, val locusCount: Int) {
    val concentration = FloatArray(chemicalCount)
    val locus = FloatArray(locusCount)

    fun copy(): ChemistryState = ChemistryState(chemicalCount, locusCount).also {
        concentration.copyInto(it.concentration)
        locus.copyInto(it.locus)
    }

    companion object {
        /** Upper bound on any chemical concentration (normalised; see DESIGN.md assumption A2). */
        const val MAX_CONCENTRATION = 1f
    }
}
