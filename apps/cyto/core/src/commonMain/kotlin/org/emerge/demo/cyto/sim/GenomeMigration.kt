package org.emerge.demo.cyto.sim

/**
 * Upgrades genomes written against an older gene model to the current one ([GeneCodec.GENOME_VERSION]).
 *
 * Cyto genomes are player-authored and player-evolved — they are the save-worthy artefact, not a derived
 * cache — so a change to the gene model has to carry them forward rather than invalidate them. The
 * contract is deliberately weaker than byte-for-byte behavioural preservation, which is not achievable
 * across a change of this kind: **an upgraded genome must load, and must be coherent** (every gene still
 * names a real reaction, and the organism's metabolic subject matter — which bonds it cares about — is
 * preserved), but it may well behave differently, because the physics it was tuned against changed.
 *
 * ## v1 → v2: the chemistry inversion
 *
 * Pre-inversion (v1), breaking a bond *released* energy (`Break <bond>` in **source** position) and building
 * one *cost* energy (`FormBond <a> <b>` in **action** position). Post-inversion (v2) it is the other way
 * round: synthesis is the energy source ([EnergySource.FormBond]) and breaking is a costed action
 * ([ActionType.BreakBond]). See HYDROTHERMAL_CHEMISTRY_PLAN.md, and [EnergySource] for why both halves had
 * to move together — keeping either old primitive alongside its new mirror opens a perpetual-motion loop.
 *
 * The two legacy keywords are unambiguous by **DSL position** (v1 only ever put `Break` in source position
 * and `FormBond` in action position), so no new syntax is needed to detect them. [GeneCodec] maps each one
 * to its modern counterpart as it parses, which alone handles the two single-sided cases; this object
 * exists for the third case, where a gene used *both* and the naive per-slot mapping gets the pairing
 * backwards. The three rules, and what each preserves:
 *
 * | v1 gene | v2 gene | preserved |
 * |---|---|---|
 * | 1. `Light → FormBond XY` | `Light → Break XY` | the bond acted on, and light as the funding source |
 * | 2. `Break XY → <action>` | `Bond X Y → <action>` | the action, and the bond the organism runs on |
 * | 3. `Break XY → FormBond ZW` | `Bond Z W → Break XY` | **the direction of each reaction** |
 *
 * Rule 3 is the interesting one and is *not* a straight per-slot inversion: the gene built `ZW` and broke
 * `XY`, and after migration it still builds `ZW` and breaks `XY` — only which side pays has flipped. Under
 * the naive mapping it would come out as `Bond X Y → Break ZW`, i.e. building the thing it used to
 * dismantle and dismantling the thing it used to build, which inverts the organism's chemistry a second
 * time and makes nonsense of any structure it was assembling.
 */
object GenomeMigration {

    /**
     * Finish the v1 → v2 upgrade for one gene that [GeneCodec] has already mapped slot-by-slot.
     *
     * [hadBreakSource] = the source was the legacy `Break <bond>` (already mapped to synthesis of that same
     * bond, per rule 2). [legacySynthesis] = the reaction a legacy `FormBond <a> <b>` action named, or null
     * if the action wasn't one (it has already been mapped to a break of that reaction's junction bond, per
     * rule 1). When both are present, rule 3 applies and the two must be **swapped**: the gene synthesises
     * what it used to build and breaks what it used to break.
     */
    fun reconcilePreInversion(gene: Gene, hadBreakSource: Boolean, legacySynthesis: EnergySource.FormBond?): Gene {
        // Rules 1 and 2 are single-sided: GeneCodec's per-slot mapping is already the right answer.
        if (!hadBreakSource || legacySynthesis == null) return gene
        // Rule 3. The action slot currently holds `Break <junction of the old FormBond>` (rule 1's mapping)
        // and the source slot holds synthesis of the old Break bond (rule 2's mapping) — both correct in
        // isolation, both wrong together. Swap the two bonds: synthesise what the gene built, break what it
        // broke. The old FormBond's operands (including wildcards) carry over verbatim, so the reaction
        // resolves the same reactants it always did.
        val brokenBond = (gene.source as? EnergySource.FormBond)?.bond ?: return gene
        if (legacySynthesis.bond.isEmpty()) return gene   // inert no-op synthesis: nothing to pair with
        return gene.copy(source = legacySynthesis, action = GeneAction(ActionType.BreakBond, brokenBond))
    }
}
