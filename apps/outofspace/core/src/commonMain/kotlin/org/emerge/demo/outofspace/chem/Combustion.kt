package org.emerge.demo.outofspace.chem

/**
 * How fast a gas burns, as a fraction of the fuel present at the onset temperature.
 *
 * ⚠️ **Eight times [BASE_RATE], and the factor is the one thing here that is a choice.** A solid
 * burns at its *surface* — that is what [BASE_RATE] stands in for, and why a lump's rate is a
 * property of how finely divided it is rather than of how much of it there is. A gas has no surface:
 * fuel and oxidiser are mixed at the molecular level and the whole volume reacts at once. Eight is
 * the smallest round number that makes that difference visible rather than a rounding, and it is
 * the dial to turn if gas fires read as too sluggish or too sudden.
 *
 * ⛔ **All that is left of `Combustion`** — increment 4 of `PLAN_unified_reactions.md`. The class
 * earned its existence by the fact that both its reagents came out of the air and every product went
 * back into it, which is the one thing about a reaction that is *not* a property of the reaction:
 * it is a property of where the fuel happens to be, and [Reaction]'s pass works that out per tile.
 * The six rows are in [REACTIONS], unchanged in every number.
 *
 * ⚠️ **This rate is still stated per row rather than derived**, and it is the last piece of "which
 * phase is this?" surviving in a table. Whether the principal is condensed at a tile is derivable,
 * so frozen methane ought to burn at [BASE_RATE] and methane gas at this one, from the same row. See
 * the plan's increment 4f.
 */
val COMBUSTION_BASE_RATE: Long = SCALE / 50L
