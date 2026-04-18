package org.emerge.sim.core.ecs

/**
 * Marks read paths on [EcsBuilder] that return the frozen initial snapshot, bypassing the
 * staged overlay of writes made earlier in the same frame.
 *
 * A call behind this opt-in means:
 *
 *  - You see the state exactly as it was at the start of the frame.
 *  - You will NOT see entities spawned, components written, or components removed by
 *    earlier systems in this frame.
 *  - This read is order-independent and therefore parallel-safe with respect to other
 *    systems — nothing another system does this frame can affect what you see.
 *
 * The safe default alternative is [EcsBuilder.entries] / [EcsBuilder.getComponent],
 * which overlay this-frame writes onto the initial snapshot. Prefer those unless you
 * specifically want frozen, order-independent reads.
 *
 * **When to opt in:**
 *  - Per-tick systems that want an order-independent view of last-frame state
 *    (the typical parallel-ECS read pattern; opt in once at file level).
 *  - Helpers that need the start-of-frame value of something that may have been
 *    tombstoned this frame (e.g. resolving "who died this frame" before respawn).
 *
 * **When NOT to opt in:**
 *  - Setup / scene-construction code, which is inherently linear and usually wants
 *    to see entities that were just staged (use `entries` / `getComponent` instead).
 *  - Systems that spawn and then read their own spawns later in the same frame.
 */
@RequiresOptIn(
    message = "Reads the frozen initial snapshot and bypasses staged writes made this frame. " +
        "Use EcsBuilder.entries / EcsBuilder.getComponent for the merged view, or opt in " +
        "explicitly to acknowledge the frozen-view semantics.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FUNCTION)
annotation class BypassesStagedView
