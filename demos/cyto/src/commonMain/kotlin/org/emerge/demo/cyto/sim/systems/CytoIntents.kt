package org.emerge.demo.cyto.sim.systems

import org.emerge.sim.core.EntityId

/** Two cells should be connected by a spring (a < b). Emitted by the contact system. */
data class WeldIntent(val a: EntityId, val b: EntityId)

/** A Repair-active cell touching an un-welded cell forms a weld with it (a < b), born "at 0 health"
 *  (maximum damage) but immediately healed by [heal] (the summed repair the touching cell(s) spent on it).
 *  So the new weld starts at `breakDamage - heal` and only persists if ongoing Repair keeps it below the
 *  break threshold — adhesion costs repair energy. Emitted by the biology phase (Repair action). */
data class WeldHealIntent(val a: EntityId, val b: EntityId, val heal: Float)

/** All of this cell's connections should be cut. Emitted by the Detach tap. */
data class DetachIntent(val id: EntityId)

/** A cell should divide / die. Emitted by the biology phase (CytoSoaReducer.biology), consumed by
 *  [CytoLifecycleSystem] in a later phase. [morphogen] (the fired Mitosis gene's operand, "" if none)
 *  is the species allocated **whole to one side** for asymmetric mitosis (MORPHOGENESIS.md §C);
 *  [morphogenToMother] keeps it in the mother (centred source) instead of the daughter (edge source). */
data class CellDivisionIntent(
    val id: EntityId,
    val morphogen: String = "",
    val morphogenToMother: Boolean = false,
    /** Oriented division: place the daughter along (`false`) / across (`true`) the local gradient of
     *  [axisMorphogen]; empty ⇒ unoriented (free-space placement). */
    val axisMorphogen: String = "",
    val divideAcross: Boolean = false,
    /** When true, the daughter rejects all welds from the mother — splits off as a separate 1-celled
     *  organism; mother keeps its connections intact. */
    val rejectMother: Boolean = false,
)
data class CellDestroyIntent(val id: EntityId)

/** A lysis attack: cell [attacker] shreds all biomass from each [victims] (un-welded touching cells),
 *  assimilating what it can hold. Undigestible species are forced into the attacker's cytoplasm —
 *  a metabolic burden that accumulates over time.
 *  [damage] = total energy units torn off the victims' biomass.
 *  [gear] = efficiency gear (0..EFFICIENCY_MAX_GEAR). Per MORPHOGENESIS.md §B:
 *    capture = ⌊damage × (gear+1) / (EFFICIENCY_MAX_GEAR+1)⌋ (rest forced into cytoplasm). */
data class LyseAttackIntent(
    val attacker: EntityId,
    val victims: List<EntityId>,
    val damage: Int,
    val gear: Int,
)
