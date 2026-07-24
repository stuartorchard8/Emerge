package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.Operand

/**
 * Read a genome the way the campaign's goals read it. Pure — a function of the gene list and nothing else,
 * which is what lets a [Lineage] outlive the cell it came off (see the class doc).
 */
fun lineageOf(genome: List<Gene>): Lineage {
    val convert = genome.firstOrNull { it.action.type == ActionType.Convert }
    val convertChem = convert?.action?.a
    val convertFuel = convert?.source as? EnergySource.FormBond
    // Light-powered convert reads as "not chemistry-powered yet" (null), not as a half-done reaction — the
    // competition chapter's job is to move the gene off Light entirely.
    val convertProduct = convertFuel?.product
    // The tightest `Biomass < N` any CONVERT gene runs under — the growth ceiling. All such clauses have to
    // hold, so the smallest is the one that actually bites.
    val cap = genome
        .filter { it.action.type == ActionType.Convert }
        .flatMap { it.condition.clauses }
        .mapNotNull { cl ->
            val rhs = cl.rhs
            if (cl.lhs == Operand.Biomass && cl.cmp == Comparison.Less && rhs is Operand.Constant) rhs.value else null
        }
        .minOrNull()
    val divide = genome.firstOrNull { it.action.type == ActionType.Divide }
    // The tightest `Biomass > N` floor any DIVIDE gene runs under. A cell splits its biomass between the
    // daughters, so without a floor it can divide below the rupture threshold and kill both. All clauses
    // have to hold, so the LARGEST floor is the one that actually bites (the mirror of the CONVERT cap).
    val divideMin = genome
        .filter { it.action.type == ActionType.Divide }
        .flatMap { it.condition.clauses }
        .mapNotNull { cl ->
            val rhs = cl.rhs
            if (cl.lhs == Operand.Biomass && cl.cmp == Comparison.Greater && rhs is Operand.Constant) rhs.value else null
        }
        .maxOrNull()
    val fuel = divide?.source as? EnergySource.FormBond
    // Light-powered division reads as "not chemistry-powered yet" (null), not as a half-done reaction — the
    // divide chapter's job is to move the gene off Light entirely.
    val product = fuel?.product
    // Only meaningful once both genes are complete: an unset CONVERT chemical or a Light-powered divide has
    // nothing to compare, and must not read as "no conflict".
    val conflicts = if (fuel == null || convertChem.isNullOrEmpty() || fuel.a.isEmpty() || fuel.b.isEmpty()) null
        else convertChem == fuel.a || convertChem == fuel.b
    // The exhaust-recycling gene: a Light-powered BREAK of the very molecule the division gene's fuel
    // reaction leaves behind. Both halves matter — a BREAK on some other molecule does not clear the waste,
    // and a chemistry-powered one would just be another bond to pay for.
    val breaksExhaust = !product.isNullOrEmpty() && genome.any { g ->
        g.action.type == ActionType.BreakBond &&
            g.source is EnergySource.Light &&
            g.action.a + g.action.b == product
    }
    // What the recycling gene is told to leave behind: the largest `<waste> > N` clause gating it. All clauses
    // have to hold, so the largest is the one that bites. Read off the SAME gene `breaksExhaust` found, so a
    // threshold on some unrelated break doesn't read as a reserve.
    val reserve = if (product.isNullOrEmpty()) null else genome
        .filter { g ->
            g.action.type == ActionType.BreakBond &&
                g.source is EnergySource.Light &&
                g.action.a + g.action.b == product
        }
        .flatMap { it.condition.clauses }
        .mapNotNull { cl ->
            val lhs = cl.lhs
            val rhs = cl.rhs
            if (lhs is Operand.Chem && lhs.species == product && cl.cmp == Comparison.Greater && rhs is Operand.Constant)
                rhs.value else null
        }
        .maxOrNull()
    val contract = genome.filter { it.action.type == ActionType.Contract }
    return Lineage(
        geneCount = genome.size,
        convertChem = convertChem,
        convertProduct = convertProduct,
        convertBiomassCap = cap,
        divideBiomassMinimum = divideMin,
        hasDivide = divide != null,
        hasPhotosynthesis = breaksExhaust,
        recycleReserve = if (breaksExhaust) reserve else null,
        divideWelds = genome.any { it.action.type == ActionType.Divide && !it.action.rejectMother },
        divideProduct = product,
        divideFuelConflicts = conflicts,
        // "Runs on chemistry, not daylight" — the muscle keeps beating through the night. Since the chemistry
        // inversion that means a synthesis-powered source rather than a break-powered one.
        contractOnChem = contract.any { it.source is EnergySource.FormBond },
        contractOnMarked = contract.any { g ->
            g.condition.clauses.any { cl ->
                val lhs = cl.lhs
                lhs is Operand.Chem && lhs.species == "bb" && cl.cmp == Comparison.Greater
            }
        },
    )
}
