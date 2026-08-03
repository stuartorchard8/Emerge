package org.emerge.demo.outofspace

import org.emerge.sim.core.SimInput

data class OutofspaceInput(val edits: List<Edit> = emptyList()) : SimInput {
    companion object {
        val EMPTY = OutofspaceInput()
    }
}
