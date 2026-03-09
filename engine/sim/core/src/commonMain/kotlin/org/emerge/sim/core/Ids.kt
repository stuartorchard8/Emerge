package org.emerge.sim.core

data class EntityId(val value: Int) {
    init {
        require(value >= 0) { "EntityId must be >= 0, got $value" }
    }
}

data class PlayerId(val value: Int) {
    init {
        require(value >= 0) { "PlayerId must be >= 0, got $value" }
    }
}

data class TeamId(val value: Int) {
    init {
        require(value >= 0) { "TeamId must be >= 0, got $value" }
    }
}

data class Tick(val value: Long) {
    init {
        require(value >= 0L) { "Tick must be >= 0, got $value" }
    }
}

