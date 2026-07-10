package org.emerge.demo.norns.world

/**
 * A deliberately minimal world: a regenerating food supply the creature can sense and consume.
 * Spatial layout, objects, and movement belong to the (deferred) world + render phase — this
 * abstracts foraging down to "is there food to eat right now", which is enough to exercise the
 * embodied survival loop (sense food → decide → eat → drive falls → live).
 */
class World(private val foodPeriod: Int = 4) {
    var ticks: Int = 0
        private set
    var foodAvailable: Boolean = true
        private set

    /** Advance the world; food reappears every [foodPeriod] ticks if it was eaten. */
    fun step() {
        ticks++
        if (ticks % foodPeriod == 0) foodAvailable = true
    }

    /** Consume the available food, if any. Returns true if there was food to eat. */
    fun consumeFood(): Boolean {
        if (!foodAvailable) return false
        foodAvailable = false
        return true
    }
}
