package org.emerge.demo.cyto.sim.systems

import org.emerge.sim.core.EntityId

// NB: weld / weld-heal / detach / division are NOT builder events — the SoA pipeline records them into
// `CytoPipelineState` (weldLo/weldHi, weldHealByPair, state.divide/destroy) and `applyLifecycle` consumes
// them there. The former WeldIntent / WeldHealIntent / DetachIntent / CellDivisionIntent event classes were
// removed when the AoS lifecycle round-trip was deleted (2026-07-09). Only the two intents below remain as
// builder events, both from the interaction/lyse paths.

/** A cell should be destroyed. Emitted by the interaction system (Delete tap), drained by the reducer. */
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
