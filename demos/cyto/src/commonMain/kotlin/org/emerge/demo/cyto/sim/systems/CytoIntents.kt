package org.emerge.demo.cyto.sim.systems

import org.emerge.sim.core.EntityId

/** Two cells should be connected by a spring (a < b). Emitted by the contact system. */
data class WeldIntent(val a: EntityId, val b: EntityId)

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
)
data class CellDestroyIntent(val id: EntityId)
