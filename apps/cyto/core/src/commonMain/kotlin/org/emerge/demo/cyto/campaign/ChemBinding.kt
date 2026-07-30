package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand

/**
 * **Rehoming a curated genome onto the player's chemistry.**
 *
 * The Act II chapters teach by *inserting* a ready-made subsystem ("+ ADD HOLD TOGETHER"), and every one of
 * those subsystems was authored against the campaign autotroph's `r`/`g` fuel — e.g. the Repair gene is
 * `FormBond(r,g) : rg > 0 : Repair`. The new campaign has no fixed chemistry: the player picks their own
 * fuel pair back in `ch01-divide`, and by `ch05-reclaim` their whole lineage runs on it. Dropping an
 * `r`/`g` gene into a `b`/`g` organism inserts a gene that cannot fire, so the subsystems have to be
 * authored **abstractly** and bound to the player's atoms at insert time.
 *
 * Templates are therefore written in placeholder atoms — [X], [Y], [Z] — and a [ChemBinding] maps them onto
 * real ones. Because a molecule token is just a string of atom characters (`"xy"`, `"xx"`, `"zy"`), the
 * substitution is a **per-character rewrite** and works for every molecule over the alphabet without
 * enumerating them.
 *
 *  - [X], [Y] — the two atoms of the player's fuel bond, in the order their bond names them.
 *  - [Z] — the remaining atom: whatever their metabolism does *not* run on. The spare, which is what the
 *    later chapters want for a morphogen/marker (the old campaign's `bb` marker and `gb` beat), because a
 *    signal molecule must not compete with the fuel.
 *
 * With no lineage to read, the binding is the **identity** (`x→r, y→g, z→b`), which reproduces the authored
 * autotroph exactly — so the pre-existing Ch1-10 chapters are unaffected by any of this.
 */
class ChemBinding private constructor(private val atomOf: Map<Char, Char>) {

    /** Rewrite one species token (`"x"`, `"xy"`, `"zz"`) onto the bound atoms. Characters with no binding
     *  pass through unchanged, so a template that names a REAL atom outright still means that atom. */
    fun species(token: String): String {
        if (token.isEmpty()) return token
        val sb = StringBuilder(token.length)
        for (c in token) sb.append(atomOf[c] ?: c)
        return sb.toString()
    }

    /** Rewrite every species token in [gene]. The three places one can hide are the energy source's reactant
     *  pair, the action's two operands, and any `Chem` operand in the condition — see the class doc for why
     *  that list is exhaustive. Derived ids (`aId`/`breakTargetId`) recompute on `copy`, so nothing stale
     *  survives the rewrite. */
    fun gene(gene: Gene): Gene = gene.copy(
        source = when (val s = gene.source) {
            is EnergySource.Light -> s
            is EnergySource.FormBond -> EnergySource.FormBond(species(s.a), species(s.b))
        },
        condition = GeneCondition(gene.condition.clauses.map { cl ->
            Clause(operand(cl.lhs), cl.cmp, operand(cl.rhs))
        }),
        action = gene.action.copy(a = species(gene.action.a), b = species(gene.action.b)),
    )

    fun genes(genes: List<Gene>): List<Gene> = genes.map { gene(it) }

    private fun operand(op: Operand): Operand =
        if (op is Operand.Chem) Operand.Chem(species(op.species)) else op

    companion object {
        /** Template atoms. Deliberately NOT members of the real alphabet, so an unbound template is inert
         *  rather than quietly meaningful: `SpeciesRegistry.id("x")` is -1. */
        const val X = "x"
        const val Y = "y"
        const val Z = "z"

        /** The authored autotroph's own chemistry — what the template placeholders were transcribed FROM,
         *  and what an unbound (or unreadable) lineage falls back to. */
        private val IDENTITY = ChemBinding(mapOf('x' to 'r', 'y' to 'g', 'z' to 'b'))

        /**
         * Bind the placeholders to the chemistry [lineage] actually runs on: its fuel bond supplies X and Y,
         * and the leftover atom is Z.
         *
         * Falls back to [IDENTITY] whenever the lineage cannot name a two-atom fuel bond — a chapter must
         * still be able to hand out a working subsystem to a player whose genome is half-built, and the
         * autotroph's own chemistry is the sane default.
         */
        fun of(lineage: Lineage?): ChemBinding {
            val bond = lineage?.divideProduct ?: return IDENTITY
            if (bond.length != 2) return IDENTITY
            val x = bond[0]
            val y = bond[1]
            // The spare: the first seeded monomer the fuel pair doesn't use. A degenerate fuel bond (x == y)
            // leaves two spares; taking the first keeps this deterministic.
            val z = CytoSeed.SEED_MONOMERS
                .map { it[0] }
                .firstOrNull { it != x && it != y }
                ?: return IDENTITY
            return ChemBinding(mapOf('x' to x, 'y' to y, 'z' to z))
        }
    }
}
