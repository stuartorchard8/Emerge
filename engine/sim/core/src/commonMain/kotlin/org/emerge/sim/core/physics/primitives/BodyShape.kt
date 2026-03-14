package org.emerge.sim.core.physics.primitives

enum class BodyShape(val wireValue: Int) {
    CIRCLE(0),
    TRIANGLE(1);

    companion object {
        fun fromWireValue(value: Int): BodyShape =
            entries.firstOrNull { it.wireValue == value } ?: CIRCLE
    }
}