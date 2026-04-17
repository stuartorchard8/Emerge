package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac

data class DamageComponent(
    val accumulated: Frac = Frac(0),
    val last: Frac = Frac(0),
    val next: Frac = Frac(0),
) {
    operator fun plus(other: DamageComponent?): DamageComponent {
        return DamageComponent(
            accumulated + other?.accumulated,
            last + other?.last,
            next + other?.next,
        )
    }
}
