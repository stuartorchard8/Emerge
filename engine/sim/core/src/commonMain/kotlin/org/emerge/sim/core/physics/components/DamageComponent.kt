package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac

data class DamageComponent(
    val old: Frac = Frac(0),
    val cur: Frac = Frac(0),
    val new: Frac = Frac(0),
) {
    operator fun plus(other: Frac): DamageComponent {
        return DamageComponent(
            old,
            cur,
            new + other,
        )
    }
}
